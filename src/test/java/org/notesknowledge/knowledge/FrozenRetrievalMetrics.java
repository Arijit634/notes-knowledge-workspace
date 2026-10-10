package org.notesknowledge.knowledge;

import java.util.List;
import java.util.Map;

/** Metrics over frozen judgments, not a claim about the model that produced a ranking. */
final class FrozenRetrievalMetrics {
    record Ranking(double recall1,double recall5,double recall10,double recall20,double mrr,double ndcg10) { }
    static Ranking score(List<String> ranked,Map<String,Integer> relevant) {
        if(ranked.stream().distinct().count()!=ranked.size()||relevant.isEmpty()||relevant.values().stream().anyMatch(g->g<1))
            throw new IllegalArgumentException("Invalid evaluation judgments");
        double reciprocal=0,dcg=0;
        for(int i=0;i<ranked.size();i++)if(relevant.containsKey(ranked.get(i))) {
            if(reciprocal==0)reciprocal=1.0/(i+1);
            if(i<10)dcg+=(Math.pow(2,relevant.get(ranked.get(i)))-1)/log2(i+2);
        }
        var ideal=relevant.values().stream().sorted(java.util.Comparator.reverseOrder()).limit(10).toList();
        double idcg=0;for(int i=0;i<ideal.size();i++)idcg+=(Math.pow(2,ideal.get(i))-1)/log2(i+2);
        return new Ranking(recall(ranked,relevant,1),recall(ranked,relevant,5),recall(ranked,relevant,10),recall(ranked,relevant,20),reciprocal,dcg/idcg);
    }
    private static double recall(List<String> ranked,Map<String,Integer> relevant,int k) {
        return (double)ranked.stream().limit(k).filter(relevant::containsKey).count()/relevant.size();
    }
    private static double log2(int n){return Math.log(n)/Math.log(2);}
    private FrozenRetrievalMetrics(){ }
}
