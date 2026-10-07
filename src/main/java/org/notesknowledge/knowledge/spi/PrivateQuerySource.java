package org.notesknowledge.knowledge.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit owner scope for durable work; every provider implementation re-resolves current source facts. */
public interface PrivateQuerySource {
    record Boundary(Instant startedAt,List<String> lifecycles) {
        public Boundary {lifecycles=List.copyOf(lifecycles);if(lifecycles.isEmpty()||!java.util.Set.of("active","archived").containsAll(lifecycles))throw new IllegalArgumentException("Invalid lifecycle scope");}
    }
    record Position(UUID noteId,UUID attachmentId) { }
    record Source(PrivateAiSourceCurrentness.Expected expected,String lifecycle,boolean aiEnabled,String modality,
        Instant createdAt,String title) {
        @Override public String toString(){return "QuerySource[REDACTED]";}
    }
    record Page(List<Source> sources,Position continuation) {public Page {sources=List.copyOf(sources);}}
    record Text(String value,Integer page) {@Override public String toString(){return "QuerySourceText[REDACTED]";}}
    Boundary capture(UUID owner,List<String> lifecycles);
    Page page(UUID owner,Boundary boundary,Position after,int limit,boolean aiOnly);
    Source note(UUID owner,UUID noteId);
    Source source(UUID owner,UUID noteId,UUID attachmentId);
    boolean matches(Source source,boolean aiRequired);
    boolean validate(List<Source> sources,boolean aiRequired);
    String fingerprint(UUID owner,Boundary boundary,boolean aiOnly);
    /** Deterministic Note/PDF text only; implementation performs byte IO outside transactions. */
    List<Text> deterministicText(Source source);
}
