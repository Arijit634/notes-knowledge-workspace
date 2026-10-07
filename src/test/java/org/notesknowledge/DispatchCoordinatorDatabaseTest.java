package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Testcontainers
class DispatchCoordinatorDatabaseTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("coordination").withUsername("synthetic").withPassword("synthetic-coordination-password");
    static HikariDataSource database;
    static JdbcTemplate jdbc;
    @BeforeAll static void database() {
        var config=new HikariConfig();config.setJdbcUrl(postgres.getJdbcUrl());config.setUsername(postgres.getUsername());config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(2);database=new HikariDataSource(config);jdbc=new JdbcTemplate(database);
    }
    @AfterAll static void close(){database.close();}
    private DispatchCoordinator coordinator() {return new DispatchCoordinator(new StaticListableBeanFactory(Map.of("database",database)).getBeanProvider(DataSource.class));}

    @Test void exclusiveNoteIsCrossInstanceAndDifferentNotesStillProceed() throws Exception {
        UUID owner=UUID.randomUUID(),note=UUID.randomUUID();
        try(var a=coordinator();var b=coordinator();var pool=Executors.newSingleThreadExecutor();var held=a.dispatch(owner,note)) {
            var waiting=pool.submit(()->{try(var handle=b.dispatch(owner,note)){return true;}});
            waiter();assertThat(waiting.isDone()).isFalse();
            try(var independent=b.dispatch(owner,UUID.randomUUID())) {independent.requireActive();}
            held.close();assertThat(waiting.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(held.safelyReleased()).isTrue();held.close();assertThat(held.safelyReleased()).isTrue();
        }
    }
    @Test void ownerAndGlobalExclusiveScopesBlockDistinctSessionDispatch() throws Exception {
        UUID owner=UUID.randomUUID();
        try(var a=coordinator();var b=coordinator();var pool=Executors.newSingleThreadExecutor()) {
            var ownerScope=a.ownerMutation(owner);
            var pending=pool.submit(()->{try(var handle=b.dispatch(owner,UUID.randomUUID())){return true;}});
            waiter();assertThat(pending.isDone()).isFalse();
            try(var other=b.dispatch(UUID.randomUUID(),UUID.randomUUID())){other.requireActive();}
            ownerScope.close();assertThat(pending.get(10,TimeUnit.SECONDS)).isTrue();
            var global=a.globalMutation();
            var globalPending=pool.submit(()->{try(var handle=b.dispatch(UUID.randomUUID(),UUID.randomUUID())){return true;}});
            waiter();assertThat(globalPending.isDone()).isFalse();global.close();assertThat(globalPending.get(10,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void boundedCoordinationExhaustionRetainsOrdinaryTransactionHeadroom() {
        try(var a=coordinator();var one=a.dispatch(UUID.randomUUID(),UUID.randomUUID());var two=a.dispatch(UUID.randomUUID(),UUID.randomUUID())) {
            assertThat(jdbc.queryForObject("select 1",Integer.class)).isEqualTo(1);
            // Dispatch capacity is fixed at two independently of the ordinary pool.
            assertThatThrownBy(()->a.dispatch(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
            try(var mutation=a.ownerMutation(UUID.randomUUID())){mutation.requireActive();}
            one.requireActive();two.requireActive();
        }
    }
    @Test void quiescenceStopsEntryAndDrainsBeforePlannedMaintenanceIsSafe() throws Exception {
        try(var a=coordinator();var pool=Executors.newSingleThreadExecutor();var dispatch=a.dispatch(UUID.randomUUID(),UUID.randomUUID())) {
            var drained=pool.submit(a::quiesce);waiter();assertThat(drained.isDone()).isFalse();
            assertThatThrownBy(()->a.dispatch(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
            dispatch.close();try(var maintenance=drained.get(10,TimeUnit.SECONDS)){maintenance.requireActive();
                assertThat(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=17403 and granted",Integer.class)).isZero();}
            assertThatThrownBy(()->a.dispatch(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        }
    }
    @Test void lostSessionNeverBecomesSafelyReleasedAndRequiresFreshSession() {
        try(var a=coordinator()) {
            var lost=a.dispatch(UUID.randomUUID(),UUID.randomUUID());
            Integer pid=jdbc.queryForObject("select pid from pg_locks where locktype='advisory' and classid=17403 and granted",Integer.class);
            assertThat(jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)).isTrue();
            assertThatThrownBy(lost::requireActive).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
            lost.close();lost.close();assertThat(lost.state()).isEqualTo(DispatchCoordinator.Handle.State.POISONED);
            assertThat(lost.safelyReleased()).isFalse();
            try(var fresh=a.dispatch(UUID.randomUUID(),UUID.randomUUID())){fresh.requireActive();}
            assertThat(lost.state()).isEqualTo(DispatchCoordinator.Handle.State.POISONED);
        }
    }
    @Test void coordinationMustPrecedeEveryDatabaseTransaction() {
        try(var a=coordinator()) {
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(database));
            transaction.executeWithoutResult(status->{
                assertThatThrownBy(()->a.noteMutation(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
                assertThatThrownBy(()->a.dispatch(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
            });
        }
    }
    private void waiter() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and classid in (17401,17402,17403) and not granted)",Boolean.class))return;
            Thread.yield();
        }
        fail("No cross-session advisory waiter");
    }
}
