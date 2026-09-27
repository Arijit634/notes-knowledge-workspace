package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Fault-injection seam after an in-transaction session authority mutation. */
@Component
@IdentityCoreEnabled
class IdentitySessionTransitionCheckpoint {
    void beforeChallengeLock(HttpServletRequest request) { }
    void beforeOidcLock(HttpServletRequest request) { }
    void afterSessionMutation(HttpServletRequest request) { }
    void afterPasswordMutation(HttpServletRequest request) { }
    void afterEmailMutation(HttpServletRequest request) { }
    void afterEmailNoticeIntents(HttpServletRequest request) { }
    void afterMfaMutation(HttpServletRequest request) { }
    void afterMfaAudit(HttpServletRequest request) { }
    void afterMfaNoticeIntent(HttpServletRequest request) { }
    void afterOtherSessionRevocation(HttpServletRequest request) { }
}
