package org.notesknowledge.knowledge.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit owner scope for durable work; every provider implementation re-resolves current source facts. */
public interface PrivateQuerySource {
    record FamilyBoundary(boolean empty,Instant createdAt,UUID sourceId) {
        public FamilyBoundary {if(empty?(createdAt!=null||sourceId!=null):(createdAt==null||sourceId==null))throw new IllegalArgumentException("Invalid source boundary");}
        public static FamilyBoundary emptyFamily(){return new FamilyBoundary(true,null,null);}
    }
    record Boundary(int schemaVersion,Instant startedAt,List<String> lifecycles,FamilyBoundary notes,FamilyBoundary attachments) {
        public Boundary {lifecycles=List.copyOf(lifecycles);if(schemaVersion!=1||startedAt==null||notes==null||attachments==null
            ||lifecycles.isEmpty()||lifecycles.size()>2||!java.util.Set.of("active","archived").containsAll(lifecycles))throw new IllegalArgumentException("Invalid corpus boundary");}
    }
    /** Family, then the family's exact (creation time, source identity) order. Never authority. */
    record Position(int family,Instant createdAt,UUID sourceId) {
        public Position {if(family<0||family>1||createdAt==null||sourceId==null)throw new IllegalArgumentException("Invalid source continuation");}
    }
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
