package org.notesknowledge.knowledge;

import java.time.Instant;
import java.util.UUID;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeaseToken;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;

/** Internal durable expectations, not a source-access or provider permit. */
public final class KnowledgeWork {
    private KnowledgeWork() { }
    public enum Kind {
        NOTE("private_note_derivation","note"), ATTACHMENT("private_attachment_derivation","attachment");
        final String workClass,sourceKind;
        Kind(String workClass,String sourceKind) { this.workClass=workClass;this.sourceKind=sourceKind; }
    }
    public enum Failure {
        TRANSIENT_DEPENDENCY, INVALID_SOURCE, POLICY_BLOCKED, ATTEMPTS_EXHAUSTED;
        String code() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public record Intent(UUID id,Kind kind,PrivateAiSourceCurrentness.Expected expected,String state,int attemptCount,int maxAttempts) {
        @Override public String toString() { return "KnowledgeIntent[REDACTED]"; }
    }
    public record Claim(Intent intent,LeaseOwner owner,LeaseToken token,Instant until) {
        @Override public String toString() { return "KnowledgeClaim[REDACTED]"; }
    }
}
