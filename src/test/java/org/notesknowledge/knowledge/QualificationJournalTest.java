package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("EVALUATION") @Tag("SECURITY")
class QualificationJournalTest {
    @TempDir Path directory;
    @Test void completedResultsAndBudgetsSurviveReopening()throws Exception {
        String key=QualificationJournal.hash("synthetic-source/revision1/lineage1/chunk0");
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            var reservation=journal.reserve(key,"n76","embedding");journal.complete(reservation,new float[]{1,0,0});
        }
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            assertThat(journal.reservations("embedding")).isEqualTo(1);
            assertThat(journal.completed(key,float[].class)).containsExactly(1,0,0);
            assertThatThrownBy(()->journal.reserve(key,"n76","embedding")).isInstanceOf(IllegalStateException.class);
            assertThat(journal.sanitizedLedger().toString()).doesNotContain(key);
        }
    }
    @Test void interruptedSdkCallRemainsChargedAndCannotBeRetried()throws Exception {
        String key=QualificationJournal.hash("interrupted-synthetic-request");
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")){journal.reserve(key,"n76","embedding");}
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            assertThat(journal.reservations("embedding")).isEqualTo(1);
            assertThatThrownBy(()->journal.completed(key,float[].class)).hasMessageContaining("Uncertain request");
            assertThatThrownBy(()->journal.reserve(key,"n76","embedding")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void incompatibleDatabaseCannotReuseState()throws Exception {
        try(var ignored=new QualificationJournal(directory,"synthetic-db-v1")) { }
        assertThatThrownBy(()->new QualificationJournal(directory,"synthetic-db-v2")).hasMessageContaining("identity mismatch");
    }
    @Test void changedRevisionPolicyOrLineageUsesDistinctCacheKey()throws Exception {
        var key=QualificationJournal.hash("n76/revision1/policy1/lineage1");
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            journal.complete(journal.reserve(key,"n76","embedding"),new float[]{1,0});
            for(String changed:new String[]{"n76/revision2/policy1/lineage1","n76/revision1/policy2/lineage1","n76/revision1/policy1/lineage2"})
                assertThat(journal.completed(QualificationJournal.hash(changed),float[].class)).isNull();
        }
    }
    @Test void corruptResultCannotMasqueradeAsCompleted()throws Exception {
        String key=QualificationJournal.hash("synthetic-result");
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            journal.complete(journal.reserve(key,"n01","embedding"),new float[]{1,0});
            Files.writeString(directory.resolve(key+".result"),"[0,1]");
            assertThatThrownBy(()->journal.completed(key,float[].class)).hasMessageContaining("integrity failure");
        }
    }
    @Test void budgetCannotResetOnRestart()throws Exception {
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            for(int i=0;i<120;i++)journal.reserve(QualificationJournal.hash("synthetic"+i),"n01","embedding");
        }
        try(var journal=new QualificationJournal(directory,"synthetic-db-v1")) {
            assertThat(journal.reservations("embedding")).isEqualTo(120);
            assertThatThrownBy(()->journal.reserve(QualificationJournal.hash("one-too-many"),"n01","embedding")).hasMessageContaining("budget exhausted");
        }
    }
    @Test void abruptChildJvmTerminationDoesNotRefundAnUncertainCall()throws Exception {
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Duser.timezone=UTC","-cp",
            System.getProperty("java.class.path"),QualificationRecoveryProbe.class.getName(),directory.toString(),"interrupt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(77);
        try(var journal=new QualificationJournal(directory,"offline-recovery-v1")) {
            assertThat(journal.reservations("embedding")).isEqualTo(1);
            assertThatThrownBy(()->journal.completed(QualificationJournal.hash("uncertain-child-sdk"),float[].class)).hasMessageContaining("Uncertain request");
        }
    }
}
