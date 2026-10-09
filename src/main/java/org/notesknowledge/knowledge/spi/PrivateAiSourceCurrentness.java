package org.notesknowledge.knowledge.spi;

import java.util.UUID;

/** Metadata-only currentness seam. Expectations are never content or provider authority. */
public interface PrivateAiSourceCurrentness {
    /** Per-statement metadata bound, not a corpus-size or result ceiling. */
    int MAX_BATCH=256;
    /** revision is the Note or Attachment core revision according to the selected source; aiGeneration is inherited from Note. */
    record Expected(UUID owner,UUID noteId,UUID attachmentId,long revision,long aiGeneration,Long attachmentGeneration) {
        public Expected {
            if(owner==null||noteId==null||revision<1||aiGeneration<1
                    ||(attachmentId==null)!=(attachmentGeneration==null)
                    ||attachmentGeneration!=null&&attachmentGeneration<1) throw new IllegalArgumentException("Invalid source expectation");
        }
        @Override public String toString() { return "ExpectedPrivateSource[REDACTED]"; }
    }
    /** Caller-owned short transaction; no content, object locator or network access. */
    boolean matches(Expected expected);
    /** Bounded current-source facts, never retained authorization; caller owns a short transaction. */
    default java.util.List<Expected> matching(java.util.List<Expected> expected) {
        if(expected.size()>MAX_BATCH)throw new IllegalArgumentException("Invalid currentness batch");
        return expected.stream().filter(this::matches).toList();
    }
}
