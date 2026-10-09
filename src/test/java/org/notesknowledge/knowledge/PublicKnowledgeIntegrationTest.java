package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Tag("RETRIEVAL")
@Testcontainers @SpringBootTest
class PublicKnowledgeIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("public_work_synthetic").withUsername("synthetic_migrator").withPassword("synthetic-public-work-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired Clock clock;@Autowired PlatformTransactionManager manager;
    @Autowired PublicKnowledgeApi api;@Autowired PublicKnowledgeTransactions transactions;@Autowired PublicKnowledgeQueryApi queries;@Autowired PublicKnowledgeWorker worker;
    @BeforeEach void retire(){PublicDiscoveryFixtures.retireAll(jdbc);}
    private PublicDiscoveryFixtures.PublicRow source() {
        var p=PublicDiscoveryFixtures.publication(jdbc,"Public","# Public heading\n\nCopied public text https://example.test/data", "public",clock.instant().minusSeconds(3600));
        new TransactionTemplate(manager).executeWithoutResult(t->api.advance(p.id(),1,1));return p;
    }
    private PublicKnowledgeRepository.Claim claim(){return transactions.claim(new LeaseOwner("synthetic-a"),1,false).getFirst();}
    private List<DerivedSegment> chunks(PublicKnowledgeRepository.Claim c){var source=transactions.capture(c).orElseThrow();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new MarkdownChunker().chunk(source.markdown());}
    @Test void publicOnlySurrogateIsFencedAndRetrievalRevalidatesCurrentGeneration() {
        var p=source();var c=claim();var segments=chunks(c);assertThat(transactions.complete(c,segments)).isTrue();
        var expected=new PublicKnowledgeSource.Expected(p.id(),1,1);
        assertThat(queries.current(expected)).extracting(PublicKnowledgeQueryApi.Segment::text).anyMatch(s->s.contains("Copied public text"));
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='knowledge' and table_name in ('public_derived_representation','public_derived_segment')",String.class))
            .noneMatch(s->s.contains("note_id")||s.contains("owner")||s.contains("attachment_id")||s.contains("object"));
        jdbc.update("update publishing.publication set snapshot_revision=2,publication_generation=2 where publication_id=?",p.id());
        // Even an intentionally retained worker-current root cannot authorize old candidates.
        assertThat(queries.current(expected)).isEmpty();
        new TransactionTemplate(manager).executeWithoutResult(t->api.advance(p.id(),2,2));
        assertThat(jdbc.queryForObject("select state from knowledge.public_derived_representation where publication_id=?",String.class,p.id())).isEqualTo("obsolete");
    }
    @Test void staleLeaseCannotActivateEvenWithCurrentSourceAndReclaimUsesFreshToken() {
        var p=source();var first=claim();var segments=chunks(first);
        jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",first.id());
        var reclaimed=transactions.claim(new LeaseOwner("synthetic-b"),1,true).getFirst();assertThat(reclaimed.token()).isNotEqualTo(first.token());
        assertThat(transactions.complete(first,segments)).isFalse();assertThat(queries.current(first.expected())).isEmpty();
        assertThat(transactions.complete(reclaimed,chunks(reclaimed))).isTrue();assertThat(queries.current(reclaimed.expected())).isNotEmpty();
        assertThat(jdbc.queryForObject("select attempt_count from knowledge.knowledge_work_intent where source_publication_id=?",Integer.class,p.id())).isEqualTo(2);
    }
    @Test void invalidationWinningRootLockPreventsLateWorkerActivation()throws Exception {
        var p=source();var c=claim();var segments=chunks(c);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var denial=threads.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.queryForObject("select publication_id from publishing.publication where publication_id=? for update",UUID.class,p.id());locked.countDown();await(release);
                jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());api.invalidate(p.id());}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var completion=threads.submit(()->transactions.complete(c,segments));
            release.countDown();denial.get(15,TimeUnit.SECONDS);assertThat(completion.get(15,TimeUnit.SECONDS)).isFalse();
        }finally{release.countDown();}
        assertThat(queries.current(c.expected())).isEmpty();assertThat(jdbc.queryForObject("select count(*) from knowledge.public_derived_representation where publication_id=? and state='current'",Integer.class,p.id())).isZero();
    }
    @Test void staleQueuedWorkDoesNotReadOrPublishOldContent() {
        var p=source();jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());
        worker.poll();assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where source_publication_id=?",String.class,p.id())).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.public_derived_representation where publication_id=?",Integer.class,p.id())).isZero();
    }
    @Test void retryAndDuplicateClaimAreBoundedAndTerminalLeaseCannotWrite()throws Exception {
        var p=source();var barrier=new CyclicBarrier(2);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->{barrier.await(10,TimeUnit.SECONDS);return transactions.claim(new LeaseOwner("synthetic-a"),1,false);});
            var b=threads.submit(()->{barrier.await(10,TimeUnit.SECONDS);return transactions.claim(new LeaseOwner("synthetic-b"),1,false);});
            var claims=new ArrayList<>(a.get(15,TimeUnit.SECONDS));claims.addAll(b.get(15,TimeUnit.SECONDS));assertThat(claims).hasSize(1);
            var c=claims.getFirst();transactions.retry(c);
            assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where source_publication_id=?",String.class,p.id())).isEqualTo("retry_wait");
            assertThat(transactions.complete(c,List.of())).isFalse();assertThat(transactions.claim(new LeaseOwner("synthetic-c"),1,false)).isEmpty();
            jdbc.update("update knowledge.knowledge_work_intent set next_attempt_at=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",c.id());
            var next=claim();assertThat(next.token()).isNotEqualTo(c.token());assertThat(transactions.complete(next,chunks(next))).isTrue();assertThat(transactions.complete(next,List.of())).isFalse();
        }
    }
    @Test void publicDenialAndEnqueueRollbackWithPublishingTransaction() {
        var p=source();var c=claim();assertThat(transactions.complete(c,chunks(c))).isTrue();
        new TransactionTemplate(manager).executeWithoutResult(t->{api.advance(p.id(),2,2);t.setRollbackOnly();});
        assertThat(queries.current(c.expected())).isNotEmpty();assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent where source_publication_id=?",Integer.class,p.id())).isEqualTo(1);
    }
    @Test void reconciliationBackfillsExistingPublicationsAndAdvancesPastIneligibleAuthors() {
        for(int i=0;i<26;i++) {
            var p=PublicDiscoveryFixtures.publication(jdbc,"Public","Synthetic public text","public",clock.instant().minusSeconds(3600));
            if(i<25)jdbc.update("update identity.account set account_state='suspended',updated_at=now() where user_id=?",p.owner());
        }
        var next=transactions.reconcile(null);assertThat(next).isNotNull();
        assertThat(transactions.reconcile(next)).isNull();
        assertThat(transactions.claim(new LeaseOwner("synthetic-backfill"),10,false)).hasSize(1);
    }
    @Test void burstPublicationFixturesHaveDistinctValidHandlesAndOwnerProvenance() {
        for(int i=0;i<64;i++) {
            PublicDiscoveryFixtures.publication(jdbc,"Public","Synthetic public text","public",clock.instant().minusSeconds(3600));
        }
        var handles=jdbc.queryForList("select a.handle from publishing.publication p join profile.public_profile_projection a on a.public_profile_projection_id=p.public_profile_projection_id join profile.profile f on f.profile_id=a.profile_id where p.availability='active' and a.active and a.user_id=p.owner_user_id and f.user_id=p.owner_user_id",String.class);
        assertThat(handles).hasSize(64).doesNotHaveDuplicates()
            .allSatisfy(handle->assertThat(handle).hasSize(27).matches("^[a-z][a-z0-9_]{2,29}$"));
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic barrier timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
}
