package org.notesknowledge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transient coordination only, never authorization. No business-module repositories. */
@Component
public class DispatchCoordinator implements AutoCloseable {
    private final ObjectProvider<DataSource> database;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private HikariDataSource dispatchPool;
    private HikariDataSource mutationPool;

    public DispatchCoordinator(ObjectProvider<DataSource> database) { this.database = database; }

    public Handle dispatch(UUID owner, UUID note) {
        if (!accepting.get()) throw unavailable();
        Handle handle = acquire(true, scopes(owner, note, false));
        if (!accepting.get()) { handle.close(); throw unavailable(); }
        return handle;
    }
    /** Bounded deterministic advisory-key ordering, including collision deduplication. */
    public Handle dispatchMany(UUID owner, java.util.Collection<UUID> notes) {
        if (!accepting.get()) throw unavailable();
        Handle handle = acquire(true, multiScopes(owner, notes));
        if (!accepting.get()) { handle.close(); throw unavailable(); }
        return handle;
    }
    private static List<Scope> multiScopes(UUID owner, java.util.Collection<UUID> notes) {
        if (owner==null || notes==null || notes.isEmpty() || notes.size()>12 || notes.stream().anyMatch(java.util.Objects::isNull)) throw unavailable();
        var result=new ArrayList<Scope>();
        result.add(new Scope(17401,0,true)); result.add(new Scope(17402,key(owner),true));
        notes.stream().map(DispatchCoordinator::key).distinct().sorted().forEach(k->result.add(new Scope(17403,k,false)));
        return List.copyOf(result);
    }
    public Handle noteMutation(UUID owner, UUID note) { return acquire(false, scopes(owner, note, false)); }
    public Handle ownerMutation(UUID owner) { return acquire(false, scopes(owner, null, true)); }
    public Handle globalMutation() { return acquire(false, List.of(new Scope(17401, 0, false))); }

    /** Irreversible local stop plus cross-instance drain. Caller retains the global scope.
     * Planned session-breaking maintenance must stop EVERY participating instance first. */
    public Handle quiesce() { accepting.set(false); return globalMutation(); }

    private static List<Scope> scopes(UUID owner, UUID note, boolean exclusiveOwner) {
        if (owner == null || (!exclusiveOwner && note == null)) throw unavailable();
        var scopes = new ArrayList<Scope>();
        scopes.add(new Scope(17401, 0, true));
        scopes.add(new Scope(17402, key(owner), !exclusiveOwner));
        if (note != null) scopes.add(new Scope(17403, key(note), false));
        return List.copyOf(scopes);
    }
    private static int key(UUID id) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(id.toString().getBytes(StandardCharsets.UTF_8))).getInt(); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private synchronized HikariDataSource pool(boolean dispatch) throws SQLException {
        HikariDataSource current = dispatch ? dispatchPool : mutationPool;
        if (current != null) return current;
        // Separate fixed capacity: waiting mutations never exhaust dispatch or ordinary transaction connections.
        var primary = database.getObject().unwrap(HikariDataSource.class);
        var config = new HikariConfig();
        // Do not copy the running pool's sealed/lifecycle state into a configurable pool.
        if (primary.getDataSource() != null) config.setDataSource(primary.getDataSource());
        else {
            config.setJdbcUrl(primary.getJdbcUrl());
            config.setDriverClassName(primary.getDriverClassName());
            config.setDataSourceClassName(primary.getDataSourceClassName());
            config.setDataSourceProperties((java.util.Properties) primary.getDataSourceProperties().clone());
        }
        config.setUsername(primary.getUsername()); config.setPassword(primary.getPassword());
        config.setSchema(primary.getSchema()); config.setCatalog(primary.getCatalog());
        config.setConnectionInitSql(primary.getConnectionInitSql());
        config.setPoolName(dispatch ? "dispatch-coordination" : "mutation-coordination");
        config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
        config.setRegisterMbeans(false);
        config.setMetricRegistry(null); config.setMetricsTrackerFactory(null);
        var created = new HikariDataSource();
        config.copyStateTo(created);
        if (dispatch) dispatchPool = created; else mutationPool = created;
        return created;
    }
    private Handle acquire(boolean dispatch, List<Scope> scopes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        Handle handle = null;
        try {
            var pool = pool(dispatch);
            handle = new Handle(pool, pool.getConnection());
            for (Scope scope : scopes) handle.lock(scope);
            handle.requireActive();
            return handle;
        } catch (SQLException | RuntimeException failure) {
            if (handle != null) { handle.poison(); handle.close(); }
            throw unavailable(); // Never expose JDBC diagnostics, lock keys or connection configuration.
        }
    }
    static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    private record Scope(int family, int key, boolean shared) { }

    public static final class Handle implements AutoCloseable {
        public enum State { ACTIVE, SAFELY_RELEASED, POISONED }
        private final HikariDataSource pool;
        private final Connection connection;
        private final int backend;
        private final List<Scope> held = new ArrayList<>();
        private State state = State.ACTIVE;
        private boolean closed;
        private Handle(HikariDataSource pool, Connection connection) throws SQLException {
            this.pool = pool; this.connection = connection;
            try {
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(2);
                    statement.execute("set lock_timeout='5s'");
                    try (var result = statement.executeQuery("select pg_backend_pid()")) { result.next(); backend = result.getInt(1); }
                }
            } catch (SQLException failure) { pool.evictConnection(connection); connection.close(); throw failure; }
        }
        private void lock(Scope scope) throws SQLException {
            String function = scope.shared ? "pg_advisory_lock_shared" : "pg_advisory_lock";
            try (var statement = connection.prepareStatement("select " + function + "(?,?)")) {
                statement.setQueryTimeout(6); statement.setInt(1, scope.family); statement.setInt(2, scope.key);
                statement.execute(); held.add(scope);
            }
        }
        public synchronized State state() { return state; }
        public synchronized boolean safelyReleased() { return closed && state == State.SAFELY_RELEASED; }
        public synchronized void requireDispatchScope(UUID owner, UUID note) {
            if (!held.equals(scopes(owner, note, false))) { poison(); throw unavailable(); }
            requireActive();
        }
        public synchronized void requireDispatchScopes(UUID owner, java.util.Collection<UUID> notes) {
            if (!held.equals(multiScopes(owner,notes))) { poison(); throw unavailable(); }
            requireActive();
        }
        private synchronized void poison() { state = State.POISONED; }
        public synchronized void requireActive() {
            if (state != State.ACTIVE || closed) throw unavailable();
            try {
                for (Scope scope : held) {
                    try (var statement = connection.prepareStatement("""
                            select pg_backend_pid()=? and exists(select 1 from pg_locks
                            where pid=pg_backend_pid() and locktype='advisory' and granted
                            and classid=?::oid and objid=?::oid and objsubid=2 and mode=?)
                            """)) {
                        statement.setQueryTimeout(2); statement.setInt(1, backend);
                        statement.setLong(2, scope.family); statement.setLong(3, Integer.toUnsignedLong(scope.key));
                        statement.setString(4, scope.shared ? "ShareLock" : "ExclusiveLock");
                        try (var result = statement.executeQuery()) {
                            if (!result.next() || !result.getBoolean(1)) throw new SQLException("Coordination unavailable");
                        }
                    }
                }
            } catch (SQLException | RuntimeException failure) { poison(); throw unavailable(); }
        }
        @Override public synchronized void close() {
            if (closed) return;
            try {
                requireActive();
                for (Scope scope : held.reversed()) {
                    String function = scope.shared ? "pg_advisory_unlock_shared" : "pg_advisory_unlock";
                    try (var statement = connection.prepareStatement("select " + function + "(?,?)")) {
                        statement.setQueryTimeout(2); statement.setInt(1, scope.family); statement.setInt(2, scope.key);
                        try (var result = statement.executeQuery()) {
                            if (!result.next() || !result.getBoolean(1)) throw new SQLException("Coordination unavailable");
                        }
                    }
                }
                // A failed pool return is unsafe as well; poison is irreversible.
                connection.close(); state = State.SAFELY_RELEASED;
            } catch (SQLException | RuntimeException failure) {
                poison(); pool.evictConnection(connection);
                try { connection.close(); } catch (SQLException ignored) { /* evicted; never reused */ }
            } finally { closed = true; }
        }
        @Override public String toString() { return "DispatchCoordination[" + state() + "]"; }
    }
    @Override public synchronized void close() {
        accepting.set(false);
        if (dispatchPool != null) dispatchPool.close();
        if (mutationPool != null) mutationPool.close();
    }
}
