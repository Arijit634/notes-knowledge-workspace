package org.notesknowledge.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Numeric aggregates only: never query text, source identity or retrieved evidence. */
@Component
final class RetrievalTelemetry {
    enum Stage { LEXICAL,EXACT,CONTEXT }
    enum Coverage { COMPLETE,TRUNCATED,CHANGED,BOUNDED,DEGRADED }
    private final MeterRegistry meters;
    private final java.util.Map<KnowledgeQueryRequest.Plan,java.util.Map<Coverage,java.util.concurrent.atomic.LongAdder>> coverage=new java.util.EnumMap<>(KnowledgeQueryRequest.Plan.class);
    RetrievalTelemetry(MeterRegistry meters){
        this.meters=meters;
        for(var plan:KnowledgeQueryRequest.Plan.values()){
            var states=new java.util.EnumMap<Coverage,java.util.concurrent.atomic.LongAdder>(Coverage.class);
            for(var state:Coverage.values())states.put(state,new java.util.concurrent.atomic.LongAdder());
            coverage.put(plan,states);
            for(var state:Coverage.values())try{io.micrometer.core.instrument.Gauge.builder("notes.workspace.retrieval.coverage",states,v->{
                long total=v.values().stream().mapToLong(java.util.concurrent.atomic.LongAdder::sum).sum();
                return total==0?Double.NaN:(double)v.get(state).sum()/total;
            }).tags("queryClass",plan.name().toLowerCase(Locale.ROOT),"state",state.name().toLowerCase(Locale.ROOT)).register(meters);}catch(RuntimeException unavailable){/* diagnostic only */}
        }
    }
    void candidates(KnowledgeQueryRequest.Plan plan,Stage stage,int count){
        try{meters.summary("notes.workspace.retrieval.candidates","queryClass",plan.name().toLowerCase(Locale.ROOT),
            "stage",stage.name().toLowerCase(Locale.ROOT)).record(Math.max(0,count));}catch(RuntimeException unavailable){/* diagnostic only */}
    }
    void coverage(KnowledgeQueryRequest.Plan plan,KnowledgeQueryResult.Coverage value,boolean degraded){
        Coverage state=degraded?Coverage.DEGRADED:value.corpusChanged()?Coverage.CHANGED:value.truncated()?Coverage.TRUNCATED:value.completed()?Coverage.COMPLETE:Coverage.BOUNDED;
        // Fraction of observed operations by declared coverage, never a last-user
        // progress value or fabricated measure of corpus recall/answer accuracy.
        coverage.get(plan).get(state).increment();
        try{meters.counter("notes.workspace.retrieval.coverage.outcome","queryClass",plan.name().toLowerCase(Locale.ROOT),
            "state",state.name().toLowerCase(Locale.ROOT)).increment();}catch(RuntimeException unavailable){/* diagnostic only */}
    }
    void preGeneration(KnowledgeQueryRequest.Plan plan,long nanos){
        try{meters.timer("notes.workspace.retrieval.pre_generation","queryClass",plan.name().toLowerCase(Locale.ROOT))
            .record(Math.max(0,nanos),java.util.concurrent.TimeUnit.NANOSECONDS);}catch(RuntimeException unavailable){/* diagnostic only */}
    }
}
