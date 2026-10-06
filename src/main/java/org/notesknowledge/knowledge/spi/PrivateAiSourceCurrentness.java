package org.notesknowledge.knowledge.spi;

import java.util.UUID;

/** Metadata-only currentness seam. Expectations are never content or provider authority. */
public interface PrivateAiSourceCurrentness {
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
}
