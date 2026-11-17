package org.notesknowledge.identity;

import java.time.Duration;
import org.notesknowledge.DatabaseIdleCoordinator;
import org.notesknowledge.DatabaseIdleParticipant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Identity-owned scheduling forecast, never an Account or session authority cache. */
@Component
@IdentityCoreEnabled
@ConditionalOnProperty(name="operations.database-idle.enabled",havingValue="true")
final class IdentityIdleMaintenance implements DatabaseIdleParticipant {
    private final JdbcClient jdbc;
    private final TransactionTemplate reads;
    private final JdbcIndexedSessionRepository sessions;
    private final DatabaseIdleCoordinator coordinator;
    IdentityIdleMaintenance(JdbcClient jdbc,PlatformTransactionManager manager,JdbcIndexedSessionRepository sessions,
            DatabaseIdleCoordinator coordinator,org.springframework.core.env.Environment environment){
        if(!"-".equals(environment.getProperty("spring.session.jdbc.cleanup-cron")))
            throw new IllegalStateException("Idle session cleanup must replace the default cron");
        this.jdbc=jdbc;this.sessions=sessions;this.coordinator=coordinator;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(3);
    }
    public String owner(){return "identity";}
    public Forecast inspect(long activityVersion){
        return reads.execute(status->{
            if(jdbc.sql("select exists(select 1 from identity.security_email_delivery where state in ('queued','claimed','retry_wait'))")
                    .query(Boolean.class).single())return Forecast.busy();
            double seconds=jdbc.sql("""
                select coalesce((min(expiry_time)-extract(epoch from clock_timestamp())*1000)/1000,1800)
                from identity.spring_session
                """).query(Double.class).single();
            return seconds<=0?Forecast.busy():Forecast.quiet(Duration.ofMillis(Math.max(1,(long)(seconds*1000))));
        });
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=60000)
    void cleanup(){
        try(var activity=DatabaseIdleCoordinator.background(coordinator)){
            if(!activity.allowed())return;
            try{sessions.cleanUpExpiredSessions();}
            catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("idle_session_cleanup_deferred");}
        }
    }
}
