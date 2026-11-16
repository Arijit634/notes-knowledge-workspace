package org.notesknowledge;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single-process scheduling hints. Every real operation still uses current DB authority. */
@Component
@ConditionalOnProperty(name="operations.database-idle.enabled",havingValue="true")
public final class DatabaseIdleCoordinator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DatabaseIdleCoordinator.class);
    private enum Mode { ACTIVE, PROBING, IDLE }
    private final ObjectProvider<DatabaseIdleParticipant> participants;
    private final long quietNanos,maximumIdleNanos;
    private final LongSupplier ticker;
    private Mode mode=Mode.ACTIVE;
    private long activityVersion,lastActivity,idleSince,idleDuration;
    private int foreground,background;
    private boolean ready;

    @org.springframework.beans.factory.annotation.Autowired
    public DatabaseIdleCoordinator(ObjectProvider<DatabaseIdleParticipant> participants,
            @Value("${operations.database-idle.quiet-period-ms:120000}") long quietMillis,
            @Value("${operations.database-idle.maximum-idle-ms:1800000}") long maximumIdleMillis) {
        this(participants,quietMillis,maximumIdleMillis,System::nanoTime);
    }
    DatabaseIdleCoordinator(ObjectProvider<DatabaseIdleParticipant> participants,long quietMillis,long maximumIdleMillis,LongSupplier ticker) {
        if(quietMillis<1000||quietMillis>300000||maximumIdleMillis<60000||maximumIdleMillis>3600000)
            throw new IllegalArgumentException("Invalid database idle schedule");
        this.participants=participants;this.quietNanos=Duration.ofMillis(quietMillis).toNanos();
        this.maximumIdleNanos=Duration.ofMillis(maximumIdleMillis).toNanos();this.ticker=ticker;
        this.lastActivity=ticker.getAsLong();
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void started(){ready=true;activate();}
    public synchronized long activityVersion(){return activityVersion;}
    public synchronized boolean idle(){return mode==Mode.IDLE;}

    public synchronized Reservation request(){activate();foreground++;return new Reservation(this,true,false);}
    private synchronized Reservation background(){
        if(!ready||mode!=Mode.ACTIVE)return new Reservation(null,false,false);
        background++;return new Reservation(this,true,true);
    }
    private synchronized Reservation work(){activate();background++;return new Reservation(this,true,true,true);}
    public static Reservation background(DatabaseIdleCoordinator value){return value==null?new Reservation(null,true,false):value.background();}
    public static Reservation work(DatabaseIdleCoordinator value){return value==null?new Reservation(null,true,false):value.work();}
    private void activate(){mode=Mode.ACTIVE;lastActivity=ticker.getAsLong();activityVersion++;}
    private synchronized void release(boolean isBackground,boolean meaningful){
        if(isBackground){if(--background<0)throw new IllegalStateException("Database activity imbalance");}
        else {if(--foreground<0)throw new IllegalStateException("Database activity imbalance");}
        if(!isBackground||meaningful)activate();
    }

    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public synchronized void closing(){ready=false;mode=Mode.ACTIVE;activityVersion++;}

    @Scheduled(fixedDelay=1000)
    public void tick(){
        final long version,probeStarted;
        synchronized(this){
            if(!ready)return;
            long now=ticker.getAsLong();
            if(mode==Mode.IDLE){if(now-idleSince>=idleDuration)activate();return;}
            if(mode!=Mode.ACTIVE||foreground!=0||background!=0||now-lastActivity<quietNanos)return;
            mode=Mode.PROBING;version=activityVersion;probeStarted=now;
        }
        boolean quiet=true;long delay=maximumIdleNanos;
        try {
            var owners=new HashSet<String>();
            for(var participant:participants.orderedStream().toList()){
                if(!owners.add(participant.owner()))throw new IllegalStateException("Duplicate idle owner");
                var forecast=participant.inspect(version);
                quiet &= forecast.quiet();
                delay=Math.min(delay,forecast.nextMaintenance().toNanos());
            }
            if(!owners.equals(Set.of("identity","knowledge")))throw new IllegalStateException("Missing idle owner");
        }catch(RuntimeException unavailable){quiet=false;LOG.warn("database_idle_check_unavailable");}
        synchronized(this){
            if(mode!=Mode.PROBING||activityVersion!=version||foreground!=0||background!=0)return;
            long now=ticker.getAsLong();
            // Forecasts use DB time. Subtract probe I/O and one scheduler tick so
            // maintenance is resumed before the earliest known deadline.
            long remaining=delay-(now-probeStarted)-Duration.ofSeconds(1).toNanos();
            if(quiet&&remaining>0){mode=Mode.IDLE;idleSince=now;idleDuration=remaining;}
            else {mode=Mode.ACTIVE;lastActivity=ticker.getAsLong();}
        }
    }
    public static final class Reservation implements AutoCloseable {
        private DatabaseIdleCoordinator owner;
        private final boolean allowed,background,meaningful;
        private Reservation(DatabaseIdleCoordinator owner,boolean allowed,boolean background){this(owner,allowed,background,false);}
        private Reservation(DatabaseIdleCoordinator owner,boolean allowed,boolean background,boolean meaningful){
            this.owner=owner;this.allowed=allowed;this.background=background;this.meaningful=meaningful;
        }
        public boolean allowed(){return allowed;}
        @Override public synchronized void close(){var current=owner;if(current!=null){owner=null;current.release(background,meaningful);}}
    }
}
