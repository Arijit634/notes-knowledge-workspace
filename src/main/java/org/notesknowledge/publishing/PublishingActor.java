package org.notesknowledge.publishing;

import java.util.UUID;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;

final class PublishingActor {
    private PublishingActor(){ }
    static UUID owner() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof IdentitySessionPrincipal p)
            ||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_USER")))
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        return p.userId();
    }
}
