package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real V019 guard and production repository, with deterministic clock skew and no provider. */
@Tag("DATABASE") @Tag("SECURITY") @Testcontainers
class KnowledgeOperationInvariantIntegrationTest {
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer postgres=
        new org.testcontainers.postgresql.PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("operation_invariants").withUsername("synthetic_migrator").withPassword("synthetic-invariant-password");
    static JdbcTemplate jdbc;
    static KnowledgeOperationRepository rows;
    static TransactionTemplate transactions;
    final List<UUID> owners=new ArrayList<>();
    UUID owner;
    static final KnowledgeOperationMaterialCipher cipher=new KnowledgeOperationMaterialCipher("v1",Base64.getEncoder().encodeToString(new byte[32]),"","");

    @BeforeAll static void migrate() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();jdbc=new JdbcTemplate(ds);
        var beans=new StaticListableBeanFactory();beans.addBean("jdbc",JdbcClient.create(jdbc));
        rows=new KnowledgeOperationRepository(beans.getBeanProvider(JdbcClient.class));
        transactions=new TransactionTemplate(new JdbcTransactionManager(ds));
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history",Integer.class)).isEqualTo(19);
    }
    @BeforeEach void prepare(){owner=account();}
    @AfterEach void cleanup(){for(UUID user:owners)jdbc.update("delete from knowledge.knowledge_work_intent where owner_user_id=?",user);}

    @Test void applicationTimestampAheadOfDatabaseDoesNotBreakClaimOrCancellation() {
        Instant created=databaseNow().plusSeconds(3600);UUID id=insert(owner,created,created.plusSeconds(3600));
        assertThat(jdbc.queryForObject("select updated_at>clock_timestamp() from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,id)).isTrue();
        var row=tx(()->rows.claim(1)).getFirst();assertThat(row.id()).isEqualTo(id);
        assertMonotonic(id,created);tx(()->{rows.cancel(owner,id);return null;});assertMonotonic(id,created);
        assertThat(state(id)).isEqualTo("cancelled");
    }

    @Test void checkpointYieldReclaimCompletionAndCleanupKeepMonotonicTimeAndFencing() {
        UUID id=insert(owner);var first=tx(()->rows.claim(1)).getFirst();Instant future=ahead(id);
        assertThat(tx(()->rows.checkpoint(first,"{}",result(first)))).isTrue();assertMonotonic(id,future);
        var checkpoint=read(id);assertThat(checkpoint.version()).isEqualTo(1);
        assertThat(tx(()->rows.checkpoint(first,"{}",result(first)))).isFalse();
        assertThat(tx(()->rows.yield(checkpoint))).isTrue();assertMonotonic(id,future);
        var second=tx(()->rows.claim(1)).getFirst();assertThat(second.lease().value().equals(first.lease().value())).as("Continuation rotates the opaque lease").isFalse();
        assertThat(tx(()->rows.current(first))).isFalse();assertThat(tx(()->rows.complete(first,"{}",result(first)))).isFalse();
        assertThat(tx(()->rows.complete(second,"{}",result(second)))).isTrue();assertMonotonic(id,future);
        assertThat(read(id).input()==null).isTrue();assertThat(read(id).result()!=null).isTrue();
        tx(()->{rows.obsolete(owner,id);return null;});assertMonotonic(id,future);
        assertThat(state(id)).isEqualTo("obsolete");assertCleared(id);
        tx(()->{rows.expire();return null;});assertMonotonic(id,future);assertThat(tx(()->rows.claim(2)).isEmpty()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"failed","obsolete"})
    void workerTerminationRemainsFencedAndMonotonic(String terminal) {
        UUID id=insert(owner);var current=tx(()->rows.claim(1)).getFirst();Instant future=ahead(id);
        assertThat(tx(()->rows.terminate(current,terminal))).isTrue();assertMonotonic(id,future);assertCleared(id);
        assertThat(tx(()->rows.checkpoint(current,"{}",result(current)))).isFalse();
        assertThat(state(id)).isEqualTo(terminal);
    }

    @Test void expiredQueuedAndCompletedMaterialIsClearedWithoutReopeningOrBackdating() {
        Instant now=databaseNow();UUID queued=insert(owner,now.minusSeconds(7200),now.minusSeconds(3600));
        UUID completed=insert(owner,now.minusSeconds(7200),now.minusSeconds(3600));
        var completedResult=frame(completed,KnowledgeOperationMaterialCipher.Kind.RESULT,1);
        jdbc.update("update knowledge.knowledge_work_intent set state='completed',next_attempt_at=null,input_ciphertext=null,input_nonce=null,input_key_version=null,result_ciphertext=?,result_nonce=?,result_key_version=?,checkpoint_version=1 where knowledge_work_intent_id=?",
            completedResult.ciphertext(),completedResult.nonce(),completedResult.keyVersion(),completed);
        Instant queuedTime=ahead(queued),completedTime=ahead(completed);
        tx(()->{rows.expire();return null;});assertCleared(queued);assertCleared(completed);
        assertThat(state(queued)).isEqualTo("obsolete");assertThat(state(completed)).isEqualTo("completed");
        assertMonotonic(queued,queuedTime);assertMonotonic(completed,completedTime);
    }

    @ParameterizedTest @ValueSource(strings={"knowledge_work_intent_id","scope_kind","owner_user_id","work_class","source_kind",
        "source_note_id","source_attachment_id","expected_revision","expected_ai_generation","expected_attachment_generation",
        "max_attempts","dedupe_key","created_at","derivation_class","target_lineage_id","operation_purpose","operation_expires_at"})
    void immutableIdentityAndLineageCannotChange(String field) {
        UUID id=insert(owner);
        String value=switch(field){
            case "knowledge_work_intent_id","owner_user_id","source_note_id","source_attachment_id"->"uuidv7()";
            case "expected_revision","expected_ai_generation","expected_attachment_generation"->"1";
            case "max_attempts"->"4";case "created_at"->"created_at-interval '1 second'";
            case "operation_expires_at"->"operation_expires_at-interval '1 second'";
            case "operation_purpose"->"'semantic_corpus'";case "dedupe_key","target_lineage_id"->"repeat('b',64)";
            case "scope_kind"->"'public'";case "work_class"->"'private_note_derivation'";
            case "source_kind"->"'note'";default->"'text_surrogate'";
        };
        rejected(()->jdbc.update("update knowledge.knowledge_work_intent set "+field+"="+value+" where knowledge_work_intent_id=?",id));
        assertThat(state(id)).isEqualTo("queued");
    }

    @ParameterizedTest @ValueSource(strings={"attempt_count","checkpoint_version","updated_at"})
    void decreasingCountersOrTimeAreStillRejected(String field) {
        UUID id=insert(owner);var row=tx(()->rows.claim(1)).getFirst();
        assertThat(tx(()->rows.checkpoint(row,"{}",result(row)))).isTrue();
        String assignment=field.equals("updated_at")?"updated_at=updated_at-interval '1 microsecond'":field+"="+field+"-1";
        rejected(()->jdbc.update("update knowledge.knowledge_work_intent set "+assignment+" where knowledge_work_intent_id=?",id));
        assertThat(read(id).version()).isEqualTo(1);assertThat(state(id)).isEqualTo("claimed");
    }

    @ParameterizedTest @ValueSource(strings={"reopen","metadata","input","result"})
    void terminalStateAndMaterialRemainImmutable(String change) {
        UUID id=insert(owner);var row=tx(()->rows.claim(1)).getFirst();
        assertThat(tx(()->rows.complete(row,"{}",result(row)))).isTrue();
        String assignment=switch(change){
            case "reopen"->"state='queued',next_attempt_at=clock_timestamp()";
            case "metadata"->"operation_metadata='{\"schemaVersion\":2}'::jsonb";
            case "input"->"input_ciphertext=decode(repeat('00',17),'hex'),input_nonce=decode(repeat('00',12),'hex'),input_key_version='v1'";
            default->"result_ciphertext=decode(repeat('00',17),'hex'),result_nonce=decode(repeat('00',12),'hex'),result_key_version='v1',checkpoint_version=checkpoint_version+1";
        };
        rejected(()->jdbc.update("update knowledge.knowledge_work_intent set "+assignment+" where knowledge_work_intent_id=?",id));
        assertThat(state(id)).isEqualTo("completed");assertThat(read(id).version()).isEqualTo(1);
    }

    @Test void concurrentClaimsAreDisjointAndOwnerScopedCancellationCannotTouchOtherWork() throws Exception {
        UUID other=account();var expected=new HashSet<UUID>();for(int i=0;i<4;i++)expected.add(insert(i%2==0?owner:other));
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();return tx(()->rows.claim(2));});
            var b=pool.submit(()->{start.await();return tx(()->rows.claim(2));});
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.size()).isEqualTo(2);assertThat(second.size()).isEqualTo(2);
            var all=new ArrayList<>(first);all.addAll(second);
            assertThat(all.stream().map(KnowledgeOperationRepository.Row::id).toList()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(all.stream().map(r->r.lease().value()).distinct().count()).isEqualTo(4);
            var foreign=all.stream().filter(r->r.owner().equals(other)).findFirst().orElseThrow();
            assertThat(tx(()->rows.owner(owner,foreign.id())).isEmpty()).isTrue();
            tx(()->{rows.cancel(owner,foreign.id());return null;});assertThat(state(foreign.id())).isEqualTo("claimed");
            assertThat(tx(()->rows.claim(2)).isEmpty()).isTrue();
        } finally {pool.shutdownNow();}
    }

    @Test void expiredLeaseReclaimChangesTokenAndRejectsOldClaimant() {
        UUID id=insert(owner);var first=tx(()->rows.claim(1)).getFirst();Instant future=ahead(id);
        jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",id);
        var next=tx(()->rows.claim(1)).getFirst();assertMonotonic(id,future);
        assertThat(next.lease().value().equals(first.lease().value())).as("Reclaim rotates the opaque lease").isFalse();assertThat(tx(()->rows.current(first))).isFalse();
        assertThat(tx(()->rows.complete(first,"{}",result(first)))).isFalse();assertThat(tx(()->rows.complete(next,"{}",result(next)))).isTrue();
    }

    @Test void retryWaitKeepsAttemptHistoryAndClaimBudgetIsBounded() {
        UUID id=insert(owner);var first=tx(()->rows.claim(1)).getFirst();Instant future=ahead(id);
        jdbc.update("update knowledge.knowledge_work_intent set state='retry_wait',next_attempt_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null where knowledge_work_intent_id=?",id);
        assertThatThrownBy(()->tx(()->rows.claim(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx(()->rows.claim(3))).isInstanceOf(IllegalArgumentException.class);
        var retry=tx(()->rows.claim(1)).getFirst();assertMonotonic(id,future);
        assertThat(jdbc.queryForObject("select attempt_count from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(tx(()->rows.current(first))).isFalse();assertThat(tx(()->rows.current(retry))).isTrue();
    }

    private UUID account(){UUID id=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),'fixture_'||uuidv7()||'@example.test','synthetic@example.test',clock_timestamp(),'active',clock_timestamp(),clock_timestamp()) returning user_id",UUID.class);owners.add(id);return id;}
    private UUID insert(UUID user){Instant created=databaseNow().minusSeconds(60);return insert(user,created,created.plusSeconds(3600));}
    private UUID insert(UUID user,Instant created,Instant expires){UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);tx(()->{rows.insert(id,user,"deterministic_corpus",created,expires,"{}",frame(id,user,KnowledgeOperationMaterialCipher.Kind.INPUT,0));return null;});return id;}
    private KnowledgeOperationMaterialCipher.Envelope frame(UUID id,KnowledgeOperationMaterialCipher.Kind kind,long version){return frame(id,owner,kind,version);}
    private KnowledgeOperationMaterialCipher.Envelope frame(UUID id,UUID user,KnowledgeOperationMaterialCipher.Kind kind,long version){return cipher.seal(new KnowledgeOperationMaterialCipher.Context(id,user,"deterministic_corpus",kind,version),new byte[]{1});}
    private KnowledgeOperationMaterialCipher.Envelope result(KnowledgeOperationRepository.Row row){return frame(row.id(),row.owner(),KnowledgeOperationMaterialCipher.Kind.RESULT,row.version()+1);}
    private KnowledgeOperationRepository.Row read(UUID id){return tx(()->rows.owner(owner,id).orElseThrow());}
    private Instant databaseNow(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
    private Instant ahead(UUID id){return jdbc.queryForObject("update knowledge.knowledge_work_intent set updated_at=greatest(updated_at,clock_timestamp())+interval '2 hours' where knowledge_work_intent_id=? returning updated_at",Timestamp.class,id).toInstant();}
    private String state(UUID id){return jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,id);}
    private void assertMonotonic(UUID id,Instant before){assertThat(jdbc.queryForObject("select updated_at from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Timestamp.class,id).toInstant()).isAfterOrEqualTo(before);}
    private void assertCleared(UUID id){assertThat(jdbc.queryForObject("select input_ciphertext is null and input_nonce is null and input_key_version is null and result_ciphertext is null and result_nonce is null and result_key_version is null and lease_token is null from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,id)).isTrue();}
    private <T>T tx(java.util.function.Supplier<T> action){return transactions.execute(s->action.get());}
    private static void rejected(Runnable action){
        // Assert only safe predicates: a wrong constraint must fail without dumping row material.
        RuntimeException failure=null;
        try {action.run();}catch(RuntimeException exception){failure=exception;}
        assertThat(failure instanceof org.springframework.dao.DataIntegrityViolationException).as("Database integrity rejection required").isTrue();
        var cause=((org.springframework.dao.DataIntegrityViolationException)failure).getMostSpecificCause();
        assertThat(cause instanceof org.postgresql.util.PSQLException).as("PostgreSQL guard rejection required").isTrue();
        assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("23514");
        assertThat(cause.getMessage().contains("Work lineage and terminal material are immutable")).as("Exact immutable-work guard must reject the mutation").isTrue();
    }
}
