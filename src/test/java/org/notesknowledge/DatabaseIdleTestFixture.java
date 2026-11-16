package org.notesknowledge;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Scheduling-only fixture; PostgreSQL authority is covered by DatabaseIdleRuntimeTest. */
public record DatabaseIdleTestFixture(DatabaseIdleCoordinator coordinator,AtomicLong ticker) {
    public static DatabaseIdleTestFixture create(){
        var factory=new StaticListableBeanFactory();
        for(String name:java.util.List.of("identity","knowledge"))factory.addBean(name,new DatabaseIdleParticipant(){
            public String owner(){return name;}public Forecast inspect(long version){return Forecast.quiet(Duration.ofMinutes(5));}
        });
        var ticker=new AtomicLong();
        var coordinator=new DatabaseIdleCoordinator(factory.getBeanProvider(DatabaseIdleParticipant.class),1000,60000,ticker::get);
        coordinator.started();return new DatabaseIdleTestFixture(coordinator,ticker);
    }
    public void quietBoundary(){ticker.addAndGet(Duration.ofSeconds(1).toNanos());coordinator.tick();}
    public void request(){try(var activity=coordinator.request()){/* no persisted authority in this fixture */}}
}
