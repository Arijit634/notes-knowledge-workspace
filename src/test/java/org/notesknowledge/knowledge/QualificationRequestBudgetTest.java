package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class QualificationRequestBudgetTest {
    @TempDir Path directory;
    @Test void allCapabilitiesShareOneBudgetAndReserveBeforeEffect()throws Exception {
        var clock=new MutableClock();var budget=new QualificationRequestBudget(directory.resolve("run"),new QualificationRequestBudget.Limits(4,100,16384,15),clock);
        int i=0;for(String kind:java.util.List.of("media","document-embedding","query-embedding","generation")){
            String reservation="%06d-reserved.json".formatted(++i);
            budget.call(kind,25,kind.endsWith("embedding")?0:8192,()->{assertThat(Files.exists(directory.resolve("run").resolve(reservation))).isTrue();return 1;});
            clock.now=clock.now.plusSeconds(15);
        }
        assertThatThrownBy(()->budget.call("query-embedding",1,0,()->1)).hasMessage("Qualification request budget denied");
        assertThat(budget.totals()).containsEntry("requests",4).containsEntry("reservedOutputTokens",16384L);
    }
    @Test void intervalAndFailedOrInterruptedCallsCannotBeHiddenOrReplayed()throws Exception {
        var clock=new MutableClock();var calls=new AtomicInteger();var path=directory.resolve("run");
        var budget=new QualificationRequestBudget(path,new QualificationRequestBudget.Limits(4,100,8192,15),clock);
        budget.call("document-embedding",1,0,calls::incrementAndGet);
        assertThat(budget.delay()).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(()->budget.call("query-embedding",1,0,calls::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        clock.now=clock.now.plusSeconds(15);
        assertThatThrownBy(()->budget.call("generation",1,8192,()->{throw new IllegalStateException("Synthetic failure");})).hasMessage("Synthetic failure");
        clock.now=clock.now.plusSeconds(15);
        assertThatThrownBy(()->budget.call("query-embedding",1,0,calls::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
        assertThat(budget.totals()).containsEntry("failed",true);
        assertThatThrownBy(()->new QualificationRequestBudget(path,new QualificationRequestBudget.Limits(4,100,8192,15),clock)).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void bytesAndOutputCeilingsDenyBeforeAnyEffect()throws Exception {
        var calls=new AtomicInteger();var budget=new QualificationRequestBudget(directory.resolve("run"),new QualificationRequestBudget.Limits(1,1,8192,15),Clock.systemUTC());
        assertThatThrownBy(()->budget.call("media",2,8192,calls::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->budget.call("generation",1,8193,calls::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-16T00:00:00Z");
        public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
}
