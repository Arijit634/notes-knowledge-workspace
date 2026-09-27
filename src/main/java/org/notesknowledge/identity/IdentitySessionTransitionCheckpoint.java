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
    void afterOidcLinkMutation(HttpServletRequest request) { }
    void afterOidcLinkAudit(HttpServletRequest request) { }
    void afterOidcLinkNotice(HttpServletRequest request) { }
    void afterEmailNoticeIntents(HttpServletRequest request) { }
    void afterMfaMutation(HttpServletRequest request) { }
    void afterMfaAudit(HttpServletRequest request) { }
    void afterMfaNoticeIntent(HttpServletRequest request) { }
    void afterOtherSessionRevocation(HttpServletRequest request) { }
    void afterSessionManagementLock(HttpServletRequest request) { }
    void beforePasswordLoginCommit(HttpServletRequest request) { }
    void beforePasswordMutationLock(HttpServletRequest request) { }
    void afterProfileDeletionConsequence(HttpServletRequest request) { }
    void afterPublishingDeletionConsequence(HttpServletRequest request) { }
    void afterDeletionCapabilityInvalidation(HttpServletRequest request) { }
    void afterDeletionSessionRevocation(HttpServletRequest request) { }
    void afterAccountDeletionMutation(HttpServletRequest request) { }
    void afterAccountDeletionAudit(HttpServletRequest request) { }
}
