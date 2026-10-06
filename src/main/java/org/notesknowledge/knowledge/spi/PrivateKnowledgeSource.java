package org.notesknowledge.knowledge.spi;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Notes owns authorization/currentness. Boundaries, locators and expectations are NOT authority. */
public interface PrivateKnowledgeSource {
    int BODY_BATCH = 5;
    int METADATA_BATCH = 500;
    int MAX_SOURCES = 10_000;
    enum Lifecycle { ACTIVE, ARCHIVED, TRASHED }
    enum Purpose { DETERMINISTIC_URL_EXTRACTION }
    record Scope(Set<Lifecycle> lifecycles) {
        public Scope { lifecycles=Set.copyOf(lifecycles); if(lifecycles.isEmpty()) throw new IllegalArgumentException("Empty source scope"); }
    }
    /** Creation-time family cutoff: (startedAt, maximum UUID), independent of visible rows and page continuation. */
    record Boundary(Instant startedAt, Instant upperCreatedAt, UUID upperId, String actorBinding, Scope scope) {
        @Override public String toString() { return "SourceBoundary[REDACTED]"; }
    }
    record Metadata(UUID noteId, long revision, Lifecycle lifecycle, long aiGeneration) {
        public String consistencyText() { return noteId+"|"+revision+"|"+lifecycle.name().toLowerCase(java.util.Locale.ROOT)+"|"+aiGeneration; }
        @Override public String toString() { return "SourceMetadata[REDACTED]"; }
    }
    record CurrentNote(Metadata metadata, String title, String markdown) {
        @Override public String toString() { return "CurrentNote[REDACTED]"; }
    }
    record BodyPage(List<CurrentNote> items, UUID continuation) { public BodyPage { items=List.copyOf(items); } }
    record MetadataPage(List<Metadata> items, UUID continuation) { public MetadataPage { items=List.copyOf(items); } }
    record Fingerprint(long count, List<Long> words) {
        public Fingerprint { words=List.copyOf(words); if(words.size()!=4) throw new IllegalArgumentException("Fingerprint width"); }
        @Override public String toString() { return "CorpusFingerprint[REDACTED]"; }
    }
    Boundary capture(Scope scope);
    BodyPage readCurrent(Boundary boundary, UUID after, Purpose purpose);
    MetadataPage readMetadata(Boundary boundary, UUID after);
    /** Atomic bounded metadata aggregate closes the second-pass traversal race. */
    Fingerprint currentFingerprint(Boundary boundary);
}
