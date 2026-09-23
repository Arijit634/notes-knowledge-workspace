package org.notesknowledge.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.notesknowledge.websupport.ApiProblemWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A persisted browser principal is never sufficient without current Account eligibility. */
@Component
@IdentityCoreEnabled
public final class IdentityEligibilityFilter extends OncePerRequestFilter {
    private final IdentityPersistence identity;
    private final ApiProblemWriter problems;

    IdentityEligibilityFilter(IdentityPersistence identity, ApiProblemWriter problems) {
        this.identity = identity;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof IdentitySessionPrincipal principal) {
            final boolean active;
            try {
                active = identity.isActive(principal.userId());
            } catch (DataAccessException exception) {
                problems.writeServiceUnavailable(request, response);
                return;
            }
            if (!active) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
