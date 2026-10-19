package org.notesknowledge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Aggregate observation only. No resource IDs, claims or authority can cross this technical boundary. */
public final class WorkQueueTelemetry {
    public enum Kind { NOTE_DERIVATION,ATTACHMENT_DERIVATION,QUERY,PUBLIC_INDEX,CAPABILITY_LINK,SECURITY_NOTICE }
    public enum State { QUEUED,CLAIMED,RETRY_WAIT,COMPLETED,SUBMITTED,FAILED,OBSOLETE,CANCELLED }
    public record Row(Kind kind,State state,long count,Instant oldestDue) { }
    private final Clock clock;
    private record Snapshot(Map<String,Long> counts,Map<Kind,Instant> oldest,boolean available){ }
    private volatile Snapshot snapshot=new Snapshot(Map.of(),Map.of(),false);
    public WorkQueueTelemetry(MeterRegistry meters,Clock clock,boolean identity){
        this.clock=clock;String prefix="notes.workspace."+(identity?"security_email":"knowledge")+".work";
        var kinds=identity?List.of(Kind.CAPABILITY_LINK,Kind.SECURITY_NOTICE):List.of(Kind.NOTE_DERIVATION,Kind.ATTACHMENT_DERIVATION,Kind.QUERY,Kind.PUBLIC_INDEX);
        var states=identity?List.of(State.QUEUED,State.CLAIMED,State.RETRY_WAIT,State.SUBMITTED,State.FAILED,State.OBSOLETE):
            List.of(State.QUEUED,State.CLAIMED,State.RETRY_WAIT,State.COMPLETED,State.FAILED,State.OBSOLETE,State.CANCELLED);
        for(var kind:kinds){
            for(var state:states)Gauge.builder(prefix+".count",this,v->{var s=v.snapshot;return s.available?s.counts.getOrDefault(kind+":"+state,0L).doubleValue():Double.NaN;})
                .tags("jobType",name(kind),"state",name(state)).register(meters);
            // "due" is exact queue scheduling eligibility, NOT an authorization
            // claim. Actual effects still revalidate every source/policy boundary.
            Gauge.builder(identity?"notes.workspace.security_email.oldest_ready_age":prefix+".oldest_age",this,v->{
                var s=v.snapshot;if(!s.available)return Double.NaN;var time=s.oldest.get(kind);return time==null?0:Math.max(0,java.time.Duration.between(time,v.clock.instant()).toMillis()/1000.);
            }).tags("jobType",name(kind),"eligibility","due").register(meters);
        }
        Gauge.builder("notes.workspace.telemetry.available",this,v->v.snapshot.available?1:0).tag("module",identity?"identity":"knowledge").register(meters);
    }
    public void publish(List<Row> rows){
        var nextCounts=new HashMap<String,Long>();var nextOldest=new EnumMap<Kind,Instant>(Kind.class);
        for(var row:rows){
            if(row.count()<0)throw new IllegalArgumentException("Invalid aggregate");
            nextCounts.merge(row.kind()+":"+row.state(),row.count(),Long::sum);
            if(row.oldestDue()!=null)nextOldest.merge(row.kind(),row.oldestDue(),(a,b)->a.isBefore(b)?a:b);
        }
        snapshot=new Snapshot(Map.copyOf(nextCounts),Map.copyOf(nextOldest),true);
    }
    public void unavailable(){snapshot=new Snapshot(Map.of(),Map.of(),false);}
    private static String name(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
}
