package org.notesknowledge;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST") @Tag("SECURITY")
class DatabaseIdleCoordinatorTest {
    final AtomicLong time=new AtomicLong();
    DatabaseIdleCoordinator coordinator(DatabaseIdleParticipant... owners){
        var factory=new StaticListableBeanFactory();
        for(int i=0;i<owners.length;i++)factory.addBean("owner"+i,owners[i]);
        return new DatabaseIdleCoordinator(factory.getBeanProvider(DatabaseIdleParticipant.class),1000,60000,time::get);
    }
    static DatabaseIdleParticipant owner(String name,LongFunction<DatabaseIdleParticipant.Forecast> forecast){
        return new DatabaseIdleParticipant(){public String owner(){return name;}public Forecast inspect(long version){return forecast.apply(version);}};
    }
    static DatabaseIdleParticipant quiet(String name){return owner(name,v->DatabaseIdleParticipant.Forecast.quiet(Duration.ofMinutes(5)));}
    void advance(long millis){time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));}
    @Test void startupRequiresBothOwnersAndUnknownForecastKeepsNormalWorkActive(){
        var missing=coordinator(quiet("identity"));
        assertThat(DatabaseIdleCoordinator.background(missing).allowed()).isFalse();
        missing.started();advance(1000);missing.tick();assertThat(missing.idle()).isFalse();
        try(var permit=DatabaseIdleCoordinator.background(missing)){assertThat(permit.allowed()).isTrue();}
        var failed=coordinator(quiet("identity"),owner("knowledge",v->{throw new IllegalStateException("SYNTHETIC_PRIVATE_ERROR");}));
        failed.started();advance(1000);failed.tick();assertThat(failed.idle()).isFalse();
    }
    @Test void quietIdleSkipsPollsButEveryRequestWakesAndHoldsItUntilCompletion(){
        var c=coordinator(quiet("identity"),quiet("knowledge"));c.started();advance(1000);c.tick();assertThat(c.idle()).isTrue();
        assertThat(DatabaseIdleCoordinator.background(c).allowed()).isFalse();
        var request=c.request();assertThat(c.idle()).isFalse();advance(1000);c.tick();assertThat(c.idle()).isFalse();
        request.close();request.close();advance(999);c.tick();assertThat(c.idle()).isFalse();advance(1);c.tick();assertThat(c.idle()).isTrue();
    }
    @Test void inFlightWorkerAndCompletionRequireANewQuietBoundary(){
        var c=coordinator(quiet("identity"),quiet("knowledge"));c.started();
        var work=DatabaseIdleCoordinator.work(c);advance(1000);c.tick();assertThat(c.idle()).isFalse();
        long before=c.activityVersion();work.close();assertThat(c.activityVersion()).isGreaterThan(before);
        advance(1000);c.tick();assertThat(c.idle()).isTrue();
        try(var newWork=DatabaseIdleCoordinator.work(c)){assertThat(newWork.allowed()).isTrue();assertThat(c.idle()).isFalse();}
    }
    @Test void emptyPollingDoesNotExtendActivityButPendingWorkPreventsIdle(){
        var c=coordinator(quiet("identity"),quiet("knowledge"));c.started();long before=c.activityVersion();
        try(var poll=DatabaseIdleCoordinator.background(c)){assertThat(poll.allowed()).isTrue();advance(1000);c.tick();assertThat(c.idle()).isFalse();}
        assertThat(c.activityVersion()).isEqualTo(before);c.tick();assertThat(c.idle()).isTrue();
        var pending=coordinator(quiet("identity"),owner("knowledge",v->DatabaseIdleParticipant.Forecast.busy()));
        pending.started();advance(1000);pending.tick();assertThat(pending.idle()).isFalse();
    }
    @Test void earliestDeadlineAndMaximumRecoveryWakeWithoutVisitor(){
        var c=coordinator(quiet("identity"),owner("knowledge",v->DatabaseIdleParticipant.Forecast.quiet(Duration.ofSeconds(7))));
        c.started();advance(1000);c.tick();advance(5999);c.tick();assertThat(c.idle()).isTrue();advance(1);c.tick();assertThat(c.idle()).isFalse();
        var bounded=coordinator(quiet("identity"),quiet("knowledge"));bounded.started();advance(1000);bounded.tick();
        advance(60000);bounded.tick();assertThat(bounded.idle()).isFalse();
    }
    @Test void requestRacingReadOnlyForecastVetoesIdleWithoutHoldingJavaMonitorAcrossDatabaseIo()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=coordinator(quiet("identity"),owner("knowledge",v->{entered.countDown();try{
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Coordination timeout");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            return DatabaseIdleParticipant.Forecast.quiet(Duration.ofMinutes(5));}));
        c.started();advance(1000);
        try(var executor=Executors.newSingleThreadExecutor()){
            var probe=executor.submit(c::tick);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(DatabaseIdleCoordinator.background(c).allowed()).isFalse();
            try(var request=c.request()){assertThat(c.idle()).isFalse();}finally{release.countDown();}
            probe.get(5,TimeUnit.SECONDS);assertThat(c.idle()).isFalse();
        }
    }
    @Test void forecastIoTimeIsNotAddedToPrivacyDeadlinesAndShutdownStopsAdmission(){
        var c=coordinator(owner("identity",v->{advance(6000);return DatabaseIdleParticipant.Forecast.quiet(Duration.ofSeconds(7));}),quiet("knowledge"));
        c.started();advance(1000);c.tick();assertThat(c.idle()).isFalse();
        c.closing();assertThat(DatabaseIdleCoordinator.background(c).allowed()).isFalse();
    }
    @Test void disabledModeIsTransparentAndInvalidBoundsFail(){
        try(var permit=DatabaseIdleCoordinator.background(null)){assertThat(permit.allowed()).isTrue();}
        assertThatThrownBy(()->new DatabaseIdleParticipant.Forecast(true,Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        var provider=new StaticListableBeanFactory().getBeanProvider(DatabaseIdleParticipant.class);
        assertThatThrownBy(()->new DatabaseIdleCoordinator(provider,0,60000,time::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
