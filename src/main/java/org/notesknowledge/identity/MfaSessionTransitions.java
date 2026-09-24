package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Rotates both primary and MFA elevation sessions, including CSRF state. */
@Component
@IdentityCoreEnabled
final class MfaSessionTransitions {
    private final SessionAuthenticationStrategy strategy;
    private final SecurityContextRepository contexts;

    MfaSessionTransitions(SessionAuthenticationStrategy strategy,
            SecurityContextRepository contexts) {
        this.strategy = strategy;
        this.contexts = contexts;
    }

    void establish(UUID userId, boolean full, HttpServletRequest request,
            HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
            session.removeAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(userId), null,
                List.of(new SimpleGrantedAuthority(full ? "ROLE_USER" : "ROLE_MFA_PENDING")));
        strategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
