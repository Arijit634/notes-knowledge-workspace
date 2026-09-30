package org.notesknowledge.notes;

import java.util.UUID;

import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;

final class NotesActor {
    private NotesActor() { }

    static UUID owner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentitySessionPrincipal principal)
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_USER".equals(authority.getAuthority()))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        return principal.userId();
    }
}
