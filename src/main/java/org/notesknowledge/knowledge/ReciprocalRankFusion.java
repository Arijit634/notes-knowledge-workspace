package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rank-only fusion. No calibrated confidence, model reranker or AI-OFF penalty. */
final class ReciprocalRankFusion {
    private ReciprocalRankFusion(){ }
    static <T> List<T> fuse(List<List<T>> signals,int maximum) {
        if(maximum<1||maximum>100)throw new IllegalArgumentException("Invalid fusion bound");
        Map<T,Double> scores=new LinkedHashMap<>();
        for(var signal:signals){if(signal.size()>100)throw new IllegalArgumentException("Invalid signal bound");int rank=0;
            var seen=new java.util.HashSet<T>();for(T item:signal){rank++;if(seen.add(item))scores.merge(item,1.0/(60+rank),Double::sum);}}
        var ordered=new ArrayList<>(scores.keySet());ordered.sort(java.util.Comparator.<T>comparingDouble(scores::get).reversed());
        return List.copyOf(ordered.subList(0,Math.min(maximum,ordered.size())));
    }
}
