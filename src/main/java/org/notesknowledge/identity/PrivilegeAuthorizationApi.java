package org.notesknowledge.identity;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Identity-owned live capability check for later public-only Moderation operations. */
@Service
@IdentityCoreEnabled
public class PrivilegeAuthorizationApi {
    private final PrivilegeAssignmentRepository assignments;

    PrivilegeAuthorizationApi(PrivilegeAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    public boolean hasActiveCapability(UUID userId, ModerationCapability capability,
            ModerationScope scope) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(scope, "scope");
        return assignments.activeFor(userId, capability, scope)
                .filter(assignment -> assignment.covers(scope)).isPresent();
    }

    public void requireActiveCapability(UUID userId, ModerationCapability capability,
            ModerationScope scope) {
        if (!hasActiveCapability(userId, capability, scope)) {
            throw new AccessDeniedException("Active moderation capability required");
        }
    }
}
