package org.notesknowledge;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Tag("DATABASE") @Tag("SECURITY")
@Testcontainers @ActiveProfiles({"test","idle-hosted"})
@SpringBootTest(properties={"identity.delivery.polling-enabled=false","knowledge.operations.scheduling-enabled=false",
    "knowledge.derivation.scheduling-enabled=false","knowledge.public.scheduling-enabled=false","operations.telemetry.enabled=false"})
class DatabaseIdleRuntimeTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
    }
    @MockitoBean org.springframework.scheduling.TaskScheduler scheduled;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired DatabaseIdleCoordinator coordinator;
    @Autowired List<DatabaseIdleParticipant> participants;
    final AtomicLong ticker=new AtomicLong();
    UUID owner;
    DatabaseIdleParticipant owner(String name){return participants.stream().filter(p->p.owner().equals(name)).findFirst().orElseThrow();}
    @BeforeEach void setup(){
        jdbc.update("delete from knowledge.search_history_entry");jdbc.update("delete from knowledge.knowledge_work_intent");
        jdbc.update("delete from identity.security_email_delivery");jdbc.update("delete from identity.spring_session");
        ReflectionTestUtils.setField(coordinator,"ticker",(java.util.function.LongSupplier)ticker::get);
        coordinator.started();
        owner=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),?,'idle@example.test',now(),'active',now(),now()) returning user_id",UUID.class,"idle-"+System.nanoTime()+"@example.test");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    SessionRepository<Session> repository(){return (SessionRepository)sessions;}
    void quietBoundary(){ticker.addAndGet(Duration.ofMinutes(2).toNanos());coordinator.tick();}
    @Test void realSessionExpiryRevocationAndScheduledPhysicalCleanupRemainAuthoritative(){
        assertThat(ReflectionTestUtils.getField(sessions,"cleanupCron")).isEqualTo("-");
        SessionRepository<Session> repository=repository();
        Session session=repository.createSession();session.setMaxInactiveInterval(Duration.ofMinutes(17));repository.save(session);
        var forecast=owner("identity").inspect(coordinator.activityVersion());
        assertThat(forecast.quiet()).isTrue();assertThat(forecast.nextMaintenance()).isBetween(Duration.ofMinutes(16),Duration.ofMinutes(18));
        quietBoundary();assertThat(coordinator.idle()).isTrue();
        try(var request=coordinator.request()){
            assertThat(sessions.findById(session.getId())).isNotNull();
            sessions.deleteById(session.getId());assertThat(sessions.findById(session.getId())).isNull();
        }
        Session expired=repository.createSession();expired.setMaxInactiveInterval(Duration.ofSeconds(1));expired.setLastAccessedTime(java.time.Instant.now().minusSeconds(60));repository.save(expired);
        assertThat(owner("identity").inspect(coordinator.activityVersion()).quiet()).isFalse();
        assertThat(sessions.findById(expired.getId())).isNull();
        Session physical=repository.createSession();physical.setMaxInactiveInterval(Duration.ofSeconds(1));physical.setLastAccessedTime(java.time.Instant.now().minusSeconds(60));repository.save(physical);
        ReflectionTestUtils.invokeMethod(owner("identity"),"cleanup");
        assertThat(jdbc.queryForObject("select count(*) from identity.spring_session",Integer.class)).isZero();
    }
    @Test void pendingSecurityEmailPreventsIdleEvenWithNoUserSessionAndIncludingFutureRetry(){
        UUID id=jdbc.queryForObject("""
            insert into identity.security_email_delivery(security_email_delivery_id,delivery_kind,subject_user_id,security_event_id,
                notice_kind,state,next_attempt_at,created_at,updated_at)
            values(uuidv7(),'security_notice',?,uuidv7(),'mfa_disabled','queued',now(),now(),now()) returning security_email_delivery_id
            """,UUID.class,owner);
        quietBoundary();assertThat(coordinator.idle()).isFalse();
        jdbc.update("update identity.security_email_delivery set state='retry_wait',next_attempt_at=now()+interval '1 hour' where security_email_delivery_id=?",id);
        quietBoundary();assertThat(coordinator.idle()).isFalse();
        jdbc.update("update identity.security_email_delivery set state='failed',next_attempt_at=null,terminal_at=clock_timestamp() where security_email_delivery_id=?",id);
        quietBoundary();assertThat(coordinator.idle()).isTrue();
    }
    @Test void knowledgePendingRetryAndExpiredLeaseRemainDatabaseFacts(){
        UUID note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'Synthetic','body','active',1,true,1,now(),now()) returning note_id",UUID.class,owner);
        UUID work=jdbc.queryForObject("insert into knowledge.knowledge_work_intent(owner_user_id,work_class,source_kind,source_note_id,expected_revision,expected_ai_generation,max_attempts,next_attempt_at,dedupe_key,target_lineage_id) values(?,'private_note_derivation','note',?,1,1,5,now(),?,?) returning knowledge_work_intent_id",UUID.class,owner,note,"a".repeat(64),"b".repeat(64));
        assertThat(owner("knowledge").inspect(coordinator.activityVersion()).quiet()).isFalse();
        jdbc.update("update knowledge.knowledge_work_intent set state='retry_wait',next_attempt_at=now()+interval '1 hour' where knowledge_work_intent_id=?",work);
        assertThat(owner("knowledge").inspect(coordinator.activityVersion()).quiet()).isFalse();
        jdbc.update("update knowledge.knowledge_work_intent set state='claimed',attempt_count=1,next_attempt_at=null,lease_owner='synthetic-worker',lease_token=uuidv7(),lease_until=now()-interval '1 second' where knowledge_work_intent_id=?",work);
        quietBoundary();assertThat(coordinator.idle()).isFalse();
        jdbc.update("update knowledge.knowledge_work_intent set state='obsolete',lease_owner=null,lease_token=null,lease_until=null where knowledge_work_intent_id=?",work);
        quietBoundary();assertThat(coordinator.idle()).isTrue();
    }
    @Test void encryptedHistoryRetentionDeadlineCannotBeLostDuringIdle(){
        jdbc.update("""
            insert into knowledge.search_history_entry(history_id,owner_user_id,question_ciphertext,question_nonce,question_key_version,created_at,expires_at)
            values(uuidv7(),?,decode(repeat('00',17),'hex'),decode(repeat('00',12),'hex'),'synthetic',now()-interval '1 day',now()+interval '7 seconds')
            """,owner);
        var forecast=owner("knowledge").inspect(coordinator.activityVersion());
        assertThat(forecast.quiet()).isTrue();assertThat(forecast.nextMaintenance()).isBetween(Duration.ofMillis(1),Duration.ofSeconds(7));
        jdbc.update("update knowledge.search_history_entry set expires_at=now()-interval '1 second'");
        assertThat(owner("knowledge").inspect(coordinator.activityVersion()).quiet()).isFalse();
    }
}
