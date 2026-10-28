package org.notesknowledge.knowledge;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.notesknowledge.websupport.ApiFailureException;

record KnowledgeQueryRequest(String query,List<String> lifecycles,boolean deduplicate,boolean includeOccurrences,org.notesknowledge.knowledge.spi.PrivateQuerySource.SavedDuring savedDuring) {
    KnowledgeQueryRequest(String query,List<String> lifecycles,boolean deduplicate,boolean includeOccurrences){this(query,lifecycles,deduplicate,includeOccurrences,null);}
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
        List<String> states=List.of("active","archived");boolean dedupe=true,occurrences=true;org.notesknowledge.knowledge.spi.PrivateQuerySource.SavedDuring period=null;
        if(input.containsKey("scope")) {
            if(!(input.get("scope") instanceof Map<?,?> scope)||!Set.of("noteLifecycle","savedDuring").containsAll(scope.keySet()))throw invalid();
            if(scope.containsKey("noteLifecycle")){
                if(!(scope.get("noteLifecycle") instanceof List<?> values)||values.stream().anyMatch(v->!(v instanceof String)))throw invalid();
                states=values.stream().map(String.class::cast).toList();
            }
            if(scope.containsKey("savedDuring")){
                if(!(scope.get("savedDuring") instanceof Map<?,?> d)||!d.keySet().equals(Set.of("from","toExclusive")))throw invalid();
                try {period=new org.notesknowledge.knowledge.spi.PrivateQuerySource.SavedDuring(instant(d.get("from")),instant(d.get("toExclusive")));}
                catch(IllegalArgumentException malformed){throw invalid();}
            }
        }
        if(input.containsKey("presentation")) {
            if(!(input.get("presentation") instanceof Map<?,?> p)||!Set.of("deduplicate","includeOccurrences").containsAll(p.keySet())||p.values().stream().anyMatch(v->!(v instanceof Boolean)))throw invalid();
            dedupe=!p.containsKey("deduplicate")||(Boolean)p.get("deduplicate");occurrences=!p.containsKey("includeOccurrences")||(Boolean)p.get("includeOccurrences");
        }
        return new KnowledgeQueryRequest(query,states,dedupe,occurrences,period);
    }
    private static java.time.Instant instant(Object value){if(!(value instanceof String s)||s.length()>35||!s.endsWith("Z"))throw invalid();try{return java.time.Instant.parse(s);}catch(java.time.DateTimeException malformed){throw invalid();}}
    Plan plan(){String q=query.toLowerCase(java.util.Locale.ROOT);
        boolean all=q.matches("(?s).*\\b(all|every|everything|list)\\b.*");
        if(all&&q.matches("(?s).*\\b(urls?|links?)\\b.*"))return Plan.DETERMINISTIC_CORPUS;
        if(all)return Plan.SEMANTIC_CORPUS;
        // Enumerating saved names/titles is a corpus request even without "all".
        // The category remains arbitrary; never special-case a profession/topic.
        if(q.matches("(?s)^\\s*(which|what)\\b.*\\b(names|titles)\\b.*\\b(did|have)\\s+i\\s+(save|saved|note|noted|write|written|record|recorded|collect|collected|mention|mentioned)\\b.*"))return Plan.SEMANTIC_CORPUS;
        if(q.matches("(?s).*\\b(what|which|when|who|password|login|budget|amount)\\b.*"))return Plan.FOCUSED;
        return Plan.RANKED;
    }
    @Override public String toString(){return "KnowledgeQueryRequest[REDACTED]";}
    private static ApiFailureException invalid(){return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);}
}
