package org.notesknowledge;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Short chunk-delivery leases serialize public denial without a transaction spanning storage I/O.
 * Hash collisions only serialize unrelated subjects; the lock never grants authority.
 */
@Component
public class PublicExposureCoordinator {
    private final ObjectProvider<DataSource> sources;
    private final ObjectProvider<JdbcClient> jdbc;
    private final java.util.concurrent.Semaphore readCapacity=new java.util.concurrent.Semaphore(4);
    PublicExposureCoordinator(ObjectProvider<DataSource> sources, ObjectProvider<JdbcClient> jdbc) {
        this.sources=sources; this.jdbc=jdbc;
    }
    private static long key(UUID owner) { return owner.getMostSignificantBits() ^ owner.getLeastSignificantBits() ^ 0x5075626c6963436fL; }
    public void denyBoundary(UUID owner) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Public mutation requires transaction");
        jdbc.getObject().sql("select pg_advisory_xact_lock(:key)").param("key",key(owner)).query(Object.class).optional();
    }
    public Lease readLease(UUID owner) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Public delivery must not span transaction");
        if(!readCapacity.tryAcquire())throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        Connection connection=null;
        try {
            connection=sources.getObject().getConnection(); connection.setAutoCommit(true);
            try (var timeout=connection.createStatement()) { timeout.execute("set lock_timeout='3s'"); }
            try (var lock=connection.prepareStatement("select pg_advisory_lock_shared(?)")) { lock.setLong(1,key(owner)); lock.execute(); }
            return new Lease(connection,key(owner),readCapacity);
        } catch (SQLException failure) {
            discard(connection);
            readCapacity.release();
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        } catch(RuntimeException failure) {
            discard(connection);
            readCapacity.release();throw failure;
        }
    }
    private static void discard(Connection connection) {
        if(connection==null)return;
        // Failed acquisition leaves session settings/lock ownership uncertain. Never return
        // that physical connection to the pool as a healthy reusable session.
        try{connection.abort(Runnable::run);}catch(SQLException ignored){ }
        finally{try{connection.close();}catch(SQLException ignored){ }}
    }
    public static final class Lease implements AutoCloseable {
        private final Connection connection; private final long key;
        private final java.util.concurrent.Semaphore capacity;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private Lease(Connection connection,long key,java.util.concurrent.Semaphore capacity){this.connection=connection;this.key=key;this.capacity=capacity;}
        @Override public void close() {
            if(!closed.compareAndSet(false,true))return;
            try {
                try(var unlock=connection.prepareStatement("select pg_advisory_unlock_shared(?)")){unlock.setLong(1,key);unlock.execute();}
                try(var reset=connection.createStatement()){reset.execute("reset lock_timeout");}
            } catch(SQLException failure){try{connection.abort(Runnable::run);}catch(SQLException ignored){ }}
            finally{try{connection.close();}catch(SQLException ignored){ }capacity.release();}
        }
        @Override public String toString(){return "PublicExposureLease[REDACTED]";}
    }
}
