package org.notesknowledge.knowledge.spi;

import java.util.List;
import java.util.UUID;

/** Current full-session scope is resolved by Notes, never supplied as a caller's owner UUID. */
public interface PrivateLexicalSearch {
    record Query(String text, PrivateKnowledgeSource.Lifecycle lifecycle, List<String> tags) {
        public Query { tags=List.copyOf(tags); }
        @Override public String toString() { return "CurrentLexicalQuery[REDACTED]"; }
    }
    record Candidate(UUID noteId, long revision, PrivateKnowledgeSource.Lifecycle lifecycle,
            long serverRank, List<String> matchLabels) {
        public Candidate { matchLabels=List.copyOf(matchLabels); }
        @Override public String toString() { return "PrivateLexicalCandidate[REDACTED]"; }
    }
    List<Candidate> search(Query query);
}
