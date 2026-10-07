package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;

record KnowledgeQueryResult(List<DeterministicItem> deterministicResults,Answer aiAnswer,List<Citation> citations,
        Coverage coverage,String degraded,boolean insufficientEvidence) {
    record Location(String kind,Integer start,Integer end,Integer page,Double timeStart,Double timeEnd,
            Double x,Double y,Double width,Double height) { }
    record Citation(UUID noteId,UUID attachmentId,Location location) { }
    record DeterministicItem(String kind,String value,List<Citation> occurrences,boolean aiEnabled) {
        public DeterministicItem {occurrences=List.copyOf(occurrences);}
        @Override public String toString(){return "DeterministicKnowledgeItem[REDACTED]";}
    }
    record Answer(List<String> claims,boolean conflicting) {public Answer {claims=List.copyOf(claims);}@Override public String toString(){return "KnowledgeAnswer[REDACTED]";}}
    record Coverage(String boundary,long inspectedSources,boolean completed,boolean corpusChanged,boolean truncated,
            boolean retryRequired,boolean classificationUncertain) { }
    /** Internal retained DTO keeps provenance out of exposed JSON; never serializes as a public response. */
    record Stored(KnowledgeQueryResult result,List<PrivateQuerySource.Source> provenance,String fingerprint,java.util.Map<String,String> lineages) {
        public Stored {provenance=List.copyOf(provenance);lineages=java.util.Map.copyOf(lineages);}
        @Override public String toString(){return "StoredKnowledgeResult[REDACTED]";}
    }
    public KnowledgeQueryResult {deterministicResults=List.copyOf(deterministicResults);citations=List.copyOf(citations);}
    @Override public String toString(){return "KnowledgeQueryResult[REDACTED]";}
}
