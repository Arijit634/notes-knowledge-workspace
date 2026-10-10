package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class QualificationTextEvaluationTest {
    @TempDir Path directory;
    static final String QUERY="Where is the fictional reserve?", KEY=QualificationJournal.hash("approved-query");
    static Set<String> approvals(){return Set.of(ProviderDispatchPolicy.queryFingerprint(QUERY));}
    static void vector(float[] v){if(v.length!=768)throw new IllegalStateException("Incompatible vector");}
    static float[] result(){float[] v=new float[768];v[0]=1;return v;}
    static QualificationJournal.Event failed(QualificationJournal j)throws Exception{return j.reserve(QualificationJournal.hash("failed-image"),"media-image","generation");}

    @Test void knownMediaFailureStaysChargedAndCachedQueryStillReauthorizes()throws Exception {
        try(var j=new QualificationJournal(directory,"synthetic-text")) {
            var failed=failed(j);var session=new QualificationTextEvaluation(j,failed,approvals(),false);
            var auth=new AtomicInteger();var calls=new AtomicInteger();
            session.query(QUERY,KEY,"q35",auth::incrementAndGet,QualificationTextEvaluationTest::vector,()->{calls.incrementAndGet();return result();});
            session.query(QUERY,KEY,"q35",auth::incrementAndGet,QualificationTextEvaluationTest::vector,()->{throw new AssertionError("Must reuse");});
            assertThat(calls).hasValue(1);assertThat(auth).hasValue(3);assertThat(j.reservations("embedding")).isEqualTo(1);
            assertThat(j.reservations("generation")).isEqualTo(1);assertThat(j.uncertainEvents()).containsExactly(failed);
        }
    }
    @Test void unexpectedUncertainQueryStopsWithoutRetryOrRefund()throws Exception {
        try(var j=new QualificationJournal(directory,"synthetic-text")) {
            var session=new QualificationTextEvaluation(j,failed(j),approvals(),false);
            assertThatThrownBy(()->session.query(QUERY,KEY,"q01",()->{},QualificationTextEvaluationTest::vector,()->{throw new IllegalStateException("Synthetic provider failure");})).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->session.query(QUERY,KEY,"q01",()->{},QualificationTextEvaluationTest::vector,()->{throw new AssertionError();})).hasMessageContaining("Unexpected uncertain");
            assertThat(j.reservations("embedding")).isEqualTo(1);assertThat(j.uncertainReservations()).isEqualTo(2);
        }
    }
    @Test void unapprovedQueryAndOfflineMissCannotReserve()throws Exception {
        try(var j=new QualificationJournal(directory,"synthetic-text")) {
            var session=new QualificationTextEvaluation(j,failed(j),approvals(),true);
            assertThatThrownBy(()->session.query("Unknown query",KEY,"q99",()->{},v->{},()->result())).hasMessageContaining("not predeclared");
            assertThatThrownBy(()->session.query(QUERY,KEY,"q35",()->{},v->{},()->result())).hasMessageContaining("cannot dispatch");
            assertThat(j.reservations("embedding")).isZero();
        }
    }
    @Test void currentnessRevocationAndLineageMismatchDenyCacheUse()throws Exception {
        try(var j=new QualificationJournal(directory,"synthetic-text")) {
            var session=new QualificationTextEvaluation(j,failed(j),approvals(),false);
            session.query(QUERY,KEY,"q35",()->{},QualificationTextEvaluationTest::vector,()->result());
            for(String boundary:List.of("source revision changed","lineage changed","AI OFF","foreign owner"))
                assertThatThrownBy(()->session.query(QUERY,KEY,"q35",()->{throw new IllegalStateException(boundary);},v->{},()->{throw new AssertionError();})).hasMessage(boundary);
            assertThatThrownBy(()->session.query(QUERY,KEY,"q35",()->{},v->{throw new IllegalStateException("Incompatible lineage");},()->result())).hasMessageContaining("Incompatible");
            assertThat(j.reservations("embedding")).isEqualTo(1);
        }
    }
    @Test void sourceMediaAndGenerationAreForbidden(){assertThatThrownBy(QualificationTextEvaluation::rejectSourceOrGenerationDispatch).isInstanceOf(IllegalStateException.class);}
    @Test void completedQuerySurvivesAbruptProcessDeathWithZeroSecondDispatch()throws Exception {
        assertThat(run("write")).isEqualTo(77);assertThat(run("read")).isZero();
        try(var j=new QualificationJournal(directory,"synthetic-query-process")) {
            assertThat(j.reservations("embedding")).isEqualTo(1);assertThat(j.reservations("generation")).isEqualTo(1);assertThat(j.uncertainReservations()).isEqualTo(1);
        }
    }
    private int run(String mode)throws Exception {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),QueryRestartProbe.class.getName(),directory.toString(),mode)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(k->k.startsWith("SPRING_AI_")||k.startsWith("SPRING_CONFIG_"));
        var child=builder.start();if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Offline probe timed out");}return child.exitValue();
    }
    public static final class QueryRestartProbe {
        public static void main(String[] args)throws Exception {
            try(var j=new QualificationJournal(Path.of(args[0]),"synthetic-query-process")) {
                var failed=args[1].equals("write")?failed(j):j.uncertainEvents().getFirst();
                var session=new QualificationTextEvaluation(j,failed,approvals(),args[1].equals("read"));
                float[] vector=session.query(QUERY,KEY,"q35",()->{},QualificationTextEvaluationTest::vector,()->{if(args[1].equals("read"))throw new AssertionError("No dispatch after restart");return result();});
                if(vector[0]!=1)throw new AssertionError("Cached query not scoreable");
                if(args[1].equals("write"))Runtime.getRuntime().halt(77);
            }
        }
    }
}
