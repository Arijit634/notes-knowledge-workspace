package org.notesknowledge.knowledge;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.notesknowledge.websupport.ApiFailureException;

record KnowledgeQueryRequest(String query,List<String> lifecycles,boolean deduplicate,boolean includeOccurrences) {
    enum Plan { RANKED, FOCUSED, SEMANTIC_CORPUS, DETERMINISTIC_CORPUS }
    KnowledgeQueryRequest {
        query=normalize(query);
        lifecycles=List.copyOf(lifecycles);if(lifecycles.isEmpty()||lifecycles.size()>2||!Set.of("active","archived").containsAll(lifecycles))throw invalid();
    }
    static String normalize(String query) {
        if(query==null||query.isBlank()||query.length()>2048||query.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw invalid();
        // NFKC can itself introduce ordinary boundary spaces (for example NBSP).
        // A final strip makes normalization idempotent across routing and durable replay.
        query=Normalizer.normalize(query.strip(),Normalizer.Form.NFKC).strip();
        if(query.isBlank()||query.length()>2048)throw invalid();
        return query;
    }
    static KnowledgeQueryRequest parse(Map<String,Object> input) {
        if(input==null||!Set.of("query","scope","presentation").containsAll(input.keySet())||!(input.get("query") instanceof String query))throw invalid();
        List<String> states=List.of("active","archived");boolean dedupe=true,occurrences=true;
        if(input.containsKey("scope")) {
            if(!(input.get("scope") instanceof Map<?,?> scope)||!scope.keySet().equals(Set.of("noteLifecycle"))||!(scope.get("noteLifecycle") instanceof List<?> values)||values.stream().anyMatch(v->!(v instanceof String)))throw invalid();
            states=values.stream().map(String.class::cast).toList();
        }
        if(input.containsKey("presentation")) {
            if(!(input.get("presentation") instanceof Map<?,?> p)||!Set.of("deduplicate","includeOccurrences").containsAll(p.keySet())||p.values().stream().anyMatch(v->!(v instanceof Boolean)))throw invalid();
            dedupe=!p.containsKey("deduplicate")||(Boolean)p.get("deduplicate");occurrences=!p.containsKey("includeOccurrences")||(Boolean)p.get("includeOccurrences");
        }
        return new KnowledgeQueryRequest(query,states,dedupe,occurrences);
    }
    Plan plan(){String q=query.toLowerCase(java.util.Locale.ROOT);
        boolean all=q.matches("(?s).*\\b(all|every|list)\\b.*");
        if(all&&q.matches("(?s).*\\b(urls?|links?)\\b.*"))return Plan.DETERMINISTIC_CORPUS;
        if(all)return Plan.SEMANTIC_CORPUS;
        if(q.matches("(?s).*\\b(what|which|when|who|password|login|budget|amount)\\b.*"))return Plan.FOCUSED;
        return Plan.RANKED;
    }
    @Override public String toString(){return "KnowledgeQueryRequest[REDACTED]";}
    private static ApiFailureException invalid(){return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);}
}
