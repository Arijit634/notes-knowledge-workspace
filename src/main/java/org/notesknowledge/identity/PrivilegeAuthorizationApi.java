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
    public record ReportScope(boolean all,java.util.List<UUID> reportIds) { }
    /** Called after current Account/session locks; held grants serialize operational revocation. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReportScope lockCurrentScope(UUID userId,ModerationCapability capability) {
        var grants=assignments.lockScopes(userId,capability);
        if(grants.isEmpty())throw new AccessDeniedException("Active moderation capability required");
        if(grants.size()>100)throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        boolean all=grants.stream().anyMatch(s->s.reportId()==null);
        return new ReportScope(all,grants.stream().map(ModerationScope::reportId).filter(Objects::nonNull).distinct().sorted().toList());
    }
}
