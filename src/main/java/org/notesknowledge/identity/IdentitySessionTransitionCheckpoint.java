package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Fault-injection seam after an in-transaction session authority mutation. */
@Component
@IdentityCoreEnabled
class IdentitySessionTransitionCheckpoint {
    void afterSessionMutation(HttpServletRequest request) { }
}
