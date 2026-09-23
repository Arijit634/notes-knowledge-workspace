package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL evidence only. The fixture SQL is never packaged as production SQL. */
@Tag("DATABASE")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class LeaseFencingDatabaseTest {

    private static final String TABLE = "public.wp2i_work_probe";
    private static final String ROLE = "wp2i_runtime";
    private static final String PASSWORD = "synthetic-wp2i-runtime-password";
    private static final Instant START = Instant.parse("2026-09-23T00:00:00Z");
    private static final LeasePolicy POLICY = new LeasePolicy(Duration.ofSeconds(30), 2);
    private static final LeaseOwner W1 = new LeaseOwner("synthetic_w1");
    private static final LeaseOwner W2 = new LeaseOwner("synthetic_w2");

    // These paths intentionally mirror the approved distinct ready and expired-lease predicates.
    private static final String READY_CLAIM = """
            with candidate as (
                select work_id from public.wp2i_work_probe
                where state in ('ready', 'retry_wait')
                  and next_attempt_at <= ? and attempt_count < max_attempts
                order by next_attempt_at, created_at, work_id
                limit ? for update skip locked
            )
            update public.wp2i_work_probe work
            set state = 'claimed', attempt_count = work.attempt_count + 1,
                lease_owner = ?, lease_token = uuidv7(), lease_until = ?,
                next_attempt_at = null
            from candidate where work.work_id = candidate.work_id
            returning work.work_id, work.lease_token, work.attempt_count,
                      work.lease_until, work.expected_generation
            """;
    private static final String EXPIRED_RECLAIM = """
            with candidate as (
                select work_id from public.wp2i_work_probe
                where state = 'claimed' and lease_until <= ?
                  and attempt_count < max_attempts
                order by lease_until, created_at, work_id
                limit ? for update skip locked
            )
            update public.wp2i_work_probe work
            set attempt_count = work.attempt_count + 1,
                lease_owner = ?, lease_token = uuidv7(), lease_until = ?,
                next_attempt_at = null
            from candidate where work.work_id = candidate.work_id
            returning work.work_id, work.lease_token, work.attempt_count,
                      work.lease_until, work.expected_generation
            """;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("wp2i_lease_probe")
            .withUsername("wp2i_migrator")
            .withPassword("synthetic-wp2i-migrator-password");

    private static JdbcTemplate administrator;
    private static JdbcTemplate runtime;
    private static TransactionTemplate transactions;
    private static final MutableUtcClock CLOCK = new MutableUtcClock();

    @BeforeAll
    static void createFixture() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        administrator = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        administrator.execute("""
                create table public.wp2i_work_probe (
                    work_id uuid primary key,
                    state text not null check (state in ('ready','retry_wait','claimed','done')),
                    attempt_count integer not null default 0 check (attempt_count >= 0),
                    max_attempts integer not null check (max_attempts > 0),
                    next_attempt_at timestamptz,
                    lease_owner varchar(64),
                    lease_token uuid,
                    lease_until timestamptz,
                    expected_generation bigint not null,
                    created_at timestamptz not null,
                    check (
                        (state in ('ready','retry_wait') and next_attempt_at is not null
                            and lease_owner is null and lease_token is null and lease_until is null)
                        or (state = 'claimed' and next_attempt_at is null
                            and lease_owner is not null and lease_token is not null
                            and lease_until is not null)
                        or (state = 'done' and next_attempt_at is null
                            and lease_owner is null and lease_token is null and lease_until is null)
                    )
                )
                """);
        administrator.execute("""
                create index wp2i_ready_probe_idx on public.wp2i_work_probe
                (next_attempt_at, created_at, work_id)
                where state in ('ready','retry_wait')
                """);
        administrator.execute("""
                create index wp2i_reclaim_probe_idx on public.wp2i_work_probe
                (lease_until, created_at, work_id)
                where state = 'claimed'
                """);
        administrator.execute("""
                create role wp2i_runtime login password 'synthetic-wp2i-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """);
        administrator.execute("grant connect on database wp2i_lease_probe to wp2i_runtime");
        administrator.execute("grant usage on schema public to wp2i_runtime");
        administrator.execute("""
                grant select, insert, update, delete on public.wp2i_work_probe to wp2i_runtime
                """);
        var runtimeDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), ROLE, PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource));
    }

    @BeforeEach
    void resetFixture() {
        administrator.execute("truncate table public.wp2i_work_probe");
        CLOCK.set(START);
    }

    @AfterAll
    static void removeFixture() {
        if (administrator != null) {
            administrator.execute("drop table if exists public.wp2i_work_probe");
        }
    }

    @Test
    void boundedReadyClaimSetsFreshTokensAndLeavesRemainder() {
        UUID first = seed(0, 3);
        seed(1, 3);
        seed(2, 3);
        List<Claim> claimed = claimReady(W1, 2);
        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(Claim::workId).contains(first);
        assertThat(new HashSet<>(claimed.stream().map(Claim::token).toList())).hasSize(2);
        assertThat(claimed).allSatisfy(claim -> {
            assertThat(claim.token().value().version()).isEqualTo(7);
            assertThat(claim.attempt()).isEqualTo(1);
            assertThat(claim.leaseUntil()).isEqualTo(START.plusSeconds(30));
        });
        assertThat(countState("ready")).isEqualTo(1);
        assertThatThrownBy(() -> claimReady(W1, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipLockedGivesConcurrentWorkersDisjointRowsWithoutWaitingForCommit()
            throws Exception {
        seed(0, 3);
        seed(1, 3);
        seed(2, 3);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Claim> first = workers.submit(() -> holdReadyClaim(W1, firstLocked, releaseFirst));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Claim> second = workers.submit(() -> claimReady(W2, 1).getFirst());
            Claim secondClaim = second.get(10, TimeUnit.SECONDS);
            assertThat(secondClaim.workId()).isNotNull();
            // The first worker's update is still uncommitted and visible as ready here.
            assertThat(countState("ready")).isEqualTo(2);
            releaseFirst.countDown();
            Claim firstClaim = first.get(10, TimeUnit.SECONDS);
            assertThat(firstClaim.workId()).isNotEqualTo(secondClaim.workId());
            assertThat(firstClaim.token()).isNotEqualTo(secondClaim.token());
            assertThat(countState("ready")).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void duplicateConcurrentClaimOfOneRowReturnsNoSecondClaim() throws Exception {
        seed(0, 3);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Claim> first = workers.submit(() -> holdReadyClaim(W1, firstLocked, releaseFirst));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<List<Claim>> second = workers.submit(() -> claimReady(W2, 1));
            assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(claimReady(W2, 1)).isEmpty();
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void reclaimFencesOldHeartbeatFinalizeAndRetryWhileCurrentWorkerCanProgress(
            CapturedOutput output) {
        UUID workId = seed(0, 3);
        Claim original = claimReady(W1, 1).getFirst();
        assertThatThrownBy(() -> heartbeat(original, CLOCK.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(heartbeat(original, CLOCK.instant().plusSeconds(20))).isZero();
        assertThat(leaseUntil(workId)).isEqualTo(START.plusSeconds(30));
        assertThat(reclaim(W2, 1)).isEmpty();
        CLOCK.set(START.plusSeconds(30));
        Claim replacement = reclaim(W2, 1).getFirst();
        assertThat(replacement.workId()).isEqualTo(workId);
        assertThat(replacement.token()).isNotEqualTo(original.token());
        assertThat(replacement.attempt()).isEqualTo(2);
        assertThat(heartbeat(original, CLOCK.instant().plusSeconds(30))).isZero();
        assertThat(finalizeClaim(original, 7).rows()).isZero();
        assertThat(retry(original, CLOCK.instant().plusSeconds(5))).isZero();
        assertThat(stateOf(workId)).isEqualTo("claimed");
        assertThat(heartbeat(replacement, CLOCK.instant().plusSeconds(35))).isEqualTo(1);
        assertThat(leaseUntil(workId)).isEqualTo(CLOCK.instant().plusSeconds(35));
        assertThat(retry(replacement, CLOCK.instant().plusSeconds(5))).isEqualTo(1);
        assertThat(stateOf(workId)).isEqualTo("retry_wait");
        assertThat(heartbeat(replacement, CLOCK.instant().plusSeconds(50))).isZero();
        assertThat(output.getAll()).doesNotContain(
                original.token().value().toString(), replacement.token().value().toString());
    }

    @Test
    void generationAndLeaseBothFenceActivationAndCurrentClaimCanFinalize() {
        UUID workId = seed(0, 3);
        Claim original = claimReady(W1, 1).getFirst();
        assertThat(finalizeClaim(original, 8).rows()).isZero();
        administrator.update("update " + TABLE + " set expected_generation = 8 where work_id = ?",
                workId);
        assertThat(finalizeClaim(original, 7).rows()).isZero();
        CLOCK.set(START.plusSeconds(30));
        Claim replacement = reclaim(W2, 1).getFirst();
        assertThat(finalizeClaim(original, 8).rows()).isZero();
        assertThat(finalizeClaim(replacement, 7).rows()).isZero();
        Mutation completed = finalizeClaim(replacement, 8);
        assertThat(completed.rows()).isEqualTo(1);
        assertThat(completed.transactionId()).isNotEqualTo(replacement.transactionId());
        assertThat(stateOf(workId)).isEqualTo("done");
        assertThat(heartbeat(replacement, CLOCK.instant().plusSeconds(60))).isZero();
    }

    @Test
    void abandonedClaimReclaimsAfterControlledExpiryAndBoundedAttemptsStopLoop() {
        UUID workId = seed(0, 2);
        Claim abandoned = claimReady(W1, 1).getFirst();
        CLOCK.set(START.plusSeconds(31));
        Claim resumed = reclaim(W2, 1).getFirst();
        assertThat(resumed.workId()).isEqualTo(workId);
        assertThat(resumed.token()).isNotEqualTo(abandoned.token());
        assertThat(reclaim(W1, 1)).isEmpty();
        CLOCK.set(START.plusSeconds(62));
        assertThat(reclaim(W1, 1)).isEmpty();
        assertThat(stateOf(workId)).isEqualTo("claimed");
    }

    @Test
    void claimCommitsBeforeSyntheticExternalEffectAndFinishUsesAnotherTransaction() {
        UUID workId = seed(0, 3);
        Claim claim = claimReady(W1, 1).getFirst();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        // A second connection sees committed state and acquires the row NOWAIT.
        String observed = transactions.execute(status -> runtime.queryForObject(
                "select state from " + TABLE + " where work_id = ? for update nowait",
                String.class, workId));
        assertThat(observed).isEqualTo("claimed");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        Mutation completion = finalizeClaim(claim, 7);
        assertThat(completion.rows()).isEqualTo(1);
        assertThat(completion.transactionId()).isNotEqualTo(claim.transactionId());
        assertThat(stateOf(workId)).isEqualTo("done");
        // A provider could have accepted the effect before an acknowledgement was lost.
        // Fenced completion protects database state; it cannot promise exactly-once delivery.
    }

    @Test
    void runtimeRoleUsesDmlOnlyAndReadyReclaimIndexesAreSeparate() {
        assertThat(runtime.queryForObject("show server_version_num", Integer.class))
                .isGreaterThanOrEqualTo(180000);
        assertThat(runtime.queryForObject("select current_user", String.class)).isEqualTo(ROLE);
        assertThat(runtime.queryForObject(
                "select has_table_privilege(current_user, ?, 'SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class, TABLE)).isTrue();
        assertThat(runtime.queryForObject(
                "select has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject(
                "select has_database_privilege(current_user, current_database(), 'CREATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject(
                "select rolsuper or rolcreatedb or rolcreaterole from pg_roles where rolname=current_user",
                Boolean.class)).isFalse();

        List<String> definitions = administrator.queryForList("""
                select indexdef from pg_indexes
                where schemaname='public' and tablename='wp2i_work_probe'
                """, String.class);
        assertThat(definitions).anySatisfy(index -> assertThat(index)
                .contains("wp2i_ready_probe_idx", "next_attempt_at", "created_at", "work_id")
                .contains("ready", "retry_wait"));
        assertThat(definitions).anySatisfy(index -> assertThat(index)
                .contains("wp2i_reclaim_probe_idx", "lease_until", "created_at", "work_id")
                .contains("claimed"));
        assertThat(READY_CLAIM.toLowerCase()).contains("for update skip locked", "limit ?");
        assertThat(EXPIRED_RECLAIM.toLowerCase()).contains("for update skip locked", "limit ?");
        assertThat(administrator.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='wp2i_work_probe'
                  and column_name in ('email','payload','note_text','session_id')
                """, Integer.class)).isZero();
        assertThat(administrator.queryForObject("""
                select count(*) from public.flyway_schema_history
                where script like '%wp2i%'
                """, Integer.class)).isZero();
    }

    private static UUID seed(int offsetSeconds, int maxAttempts) {
        return administrator.queryForObject("""
                insert into public.wp2i_work_probe
                (work_id,state,attempt_count,max_attempts,next_attempt_at,
                 expected_generation,created_at)
                values (uuidv7(),'ready',0,?,?,7,?) returning work_id
                """, UUID.class, maxAttempts, Timestamp.from(START),
                Timestamp.from(START.plusSeconds(offsetSeconds)));
    }

    private static List<Claim> claimReady(LeaseOwner owner, int requested) {
        return transactions.execute(status -> claimRows(READY_CLAIM, owner, requested));
    }

    private static List<Claim> reclaim(LeaseOwner owner, int requested) {
        return transactions.execute(status -> claimRows(EXPIRED_RECLAIM, owner, requested));
    }

    private static List<Claim> claimRows(String sql, LeaseOwner owner, int requested) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        int batch = POLICY.checkedBatchSize(requested);
        Instant now = CLOCK.instant();
        String transactionId = runtime.queryForObject("select txid_current()::text", String.class);
        return runtime.query(sql, (row, number) -> new Claim(
                row.getObject("work_id", UUID.class),
                LeaseToken.fromDatabase(row.getObject("lease_token", UUID.class)),
                row.getInt("attempt_count"), row.getTimestamp("lease_until").toInstant(),
                row.getLong("expected_generation"), transactionId),
                Timestamp.from(now), batch, owner.alias(),
                Timestamp.from(now.plus(POLICY.leaseDuration())));
    }

    private static Claim holdReadyClaim(LeaseOwner owner, CountDownLatch locked,
            CountDownLatch release) {
        return transactions.execute(status -> {
            Claim claim = claimRows(READY_CLAIM, owner, 1).getFirst();
            locked.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent claim did not reach release barrier");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Claim barrier interrupted", exception);
            }
            return claim;
        });
    }

    private static int heartbeat(Claim claim, Instant newUntil) {
        Instant now = CLOCK.instant();
        if (!newUntil.isAfter(now)) {
            throw new IllegalArgumentException("Heartbeat must extend into the future");
        }
        return transactions.execute(status -> runtime.update("""
                update public.wp2i_work_probe set lease_until = ?
                where work_id = ? and state = 'claimed' and lease_token = ?
                  and lease_until > ? and lease_until < ?
                """, Timestamp.from(newUntil), claim.workId(), claim.token().value(),
                Timestamp.from(now), Timestamp.from(newUntil)));
    }

    private static Mutation finalizeClaim(Claim claim, long expectedGeneration) {
        return transactions.execute(status -> {
            String transactionId = runtime.queryForObject("select txid_current()::text", String.class);
            int rows = runtime.update("""
                    update public.wp2i_work_probe
                    set state='done', lease_owner=null, lease_token=null, lease_until=null
                    where work_id=? and state='claimed' and lease_token=?
                      and lease_until > ? and expected_generation=?
                    """, claim.workId(), claim.token().value(),
                    Timestamp.from(CLOCK.instant()), expectedGeneration);
            return new Mutation(rows, transactionId);
        });
    }

    private static int retry(Claim claim, Instant nextAttempt) {
        return transactions.execute(status -> runtime.update("""
                update public.wp2i_work_probe
                set state='retry_wait', next_attempt_at=?,
                    lease_owner=null, lease_token=null, lease_until=null
                where work_id=? and state='claimed' and lease_token=? and lease_until > ?
                """, Timestamp.from(nextAttempt), claim.workId(), claim.token().value(),
                Timestamp.from(CLOCK.instant())));
    }

    private static int countState(String state) {
        return runtime.queryForObject("select count(*) from " + TABLE + " where state=?",
                Integer.class, state);
    }

    private static String stateOf(UUID workId) {
        return runtime.queryForObject("select state from " + TABLE + " where work_id=?",
                String.class, workId);
    }

    private static Instant leaseUntil(UUID workId) {
        return runtime.queryForObject("select lease_until from " + TABLE + " where work_id=?",
                Timestamp.class, workId).toInstant();
    }

    private record Claim(UUID workId, LeaseToken token, int attempt, Instant leaseUntil,
            long expectedGeneration, String transactionId) {
        @Override
        public String toString() {
            return "Claim[REDACTED]";
        }
    }

    private record Mutation(int rows, String transactionId) {
    }

    private static final class MutableUtcClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Fixture clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
