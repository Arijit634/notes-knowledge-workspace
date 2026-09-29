package org.notesknowledge.identity;

import java.time.Instant;
import java.util.UUID;

/** Identity-owned narrow, attributable assignment; never a session authority. */
record PrivilegeAssignment(UUID id, UUID userId, ModerationCapability capability,
        ModerationScope scope, UUID assignedByUserId, Instant grantedAt, Instant revokedAt) {
    boolean covers(ModerationScope requested) {
        return scope.reportId() == null || scope.reportId().equals(requested.reportId());
    }
}
