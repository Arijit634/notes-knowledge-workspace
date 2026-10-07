package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;

import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.notesknowledge.identity.spi.AccountDeletionPublishingConsequence;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Establishes Account, session, and current module-owned denial as one local commit. */
@Service
@IdentityCoreEnabled
final class AccountDeletionService {
    private final IdentityPersistence identity;
    private final SpringSessionAuthorityAdapter sessions;
    private final MfaSessionChallengeRepository staleRequests;
    private final AccountDeletionProfileConsequence profile;
    private final AccountDeletionPublishingConsequence publishing;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final MfaProperties policy;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final org.notesknowledge.DispatchCoordinator coordination;

    AccountDeletionService(IdentityPersistence identity, SpringSessionAuthorityAdapter sessions,
            MfaSessionChallengeRepository staleRequests,
            AccountDeletionProfileConsequence profile,
            AccountDeletionPublishingConsequence publishing,
            IdentitySessionTransitionCheckpoint checkpoint,
            MfaProperties policy, Clock clock, PlatformTransactionManager manager,
            org.notesknowledge.DispatchCoordinator coordination) {
        this.identity = identity;
        this.sessions = sessions;
        this.staleRequests = staleRequests;
        this.profile = profile;
        this.publishing = publishing;
        this.checkpoint = checkpoint;
        this.policy = policy;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.coordination = coordination;
    }

    void delete(UUID userId, boolean confirmed, HttpServletRequest request) {
        var requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        String sessionId = requestSession.getId();
        var handle = coordination.ownerMutation(userId);
        try (handle) { transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var current = sessions.lockCurrent(userId, sessionId);
            if (current == null) throw unauthenticated();
            IdentitySessionState.requireIndependentRecent((Object) current.persisted()
                    .getAttribute(IdentitySessionState.RECENT_ATTRIBUTE),
                    userId, clock.instant(), policy);
            if (!confirmed) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);

            profile.makeIneligible(userId);
            checkpoint.afterProfileDeletionConsequence(request);
            publishing.makeIneligible(userId);
            checkpoint.afterPublishingDeletionConsequence(request);

            var now = clock.instant();
            identity.revokeOutstandingForDeletion(userId, now);
            checkpoint.afterDeletionCapabilityInvalidation(request);
            sessions.revokeAll(userId);
            checkpoint.afterDeletionSessionRevocation(request);
            if (identity.markLogicallyDeleted(userId, now) != 1) {
                throw new IllegalStateException("Locked Account could not be logically deleted");
            }
            checkpoint.afterAccountDeletionMutation(request);
            identity.auditAccountDeletion(userId, now);
            checkpoint.afterAccountDeletionAudit(request);
        }); }
        if (!handle.safelyReleased()) throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        staleRequests.discardStaleRequestSession(request);
        SecurityContextHolder.clearContext();
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
