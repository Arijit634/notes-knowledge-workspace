package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Changes the current owner's credential with one committed session authority transition. */
@Service
@IdentityCoreEnabled
final class CredentialManagementService {
    private static final Duration EMAIL_CHANGE_LIFETIME = Duration.ofHours(24);
    private final IdentityPersistence identity;
    private final OidcIdentityRepository oidcLinks;
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailMaterialCipher cipher;
    private final DatabaseUuidV7Generator ids;
    private final SpringSessionAuthorityAdapter sessions;
    private final MfaSessionTransitions transitions;
    private final MfaSessionChallengeRepository staleRequests;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final MfaProperties policy;
    private final TransactionTemplate transactions;

    CredentialManagementService(IdentityPersistence identity, OidcIdentityRepository oidcLinks,
            SecurityEmailDeliveryRepository delivery, SecurityEmailMaterialCipher cipher,
            DatabaseUuidV7Generator ids,
            SpringSessionAuthorityAdapter sessions, MfaSessionTransitions transitions,
            MfaSessionChallengeRepository staleRequests,
            IdentitySessionTransitionCheckpoint checkpoint, PasswordEncoder passwords,
            Clock clock, MfaProperties policy, PlatformTransactionManager manager) {
        this.identity = identity;
        this.oidcLinks = oidcLinks;
        this.delivery = delivery;
        this.cipher = cipher;
        this.ids = ids;
        this.sessions = sessions;
        this.transitions = transitions;
        this.staleRequests = staleRequests;
        this.checkpoint = checkpoint;
        this.passwords = passwords;
        this.clock = clock;
        this.policy = policy;
        this.transactions = new TransactionTemplate(manager);
    }

    void requestEmailChange(UUID userId, String candidate, HttpServletRequest request) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        String originalSessionId = requestSession.getId();
        UUID capabilityId = ids.generate();
        String token = EmailChangeToken.issue(capabilityId);
        byte[] digest = EmailChangeToken.digest(token);
        var envelope = cipher.seal("email_change", capabilityId, token);
        transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var current = sessions.lockCurrent(userId, originalSessionId);
            if (current == null) {
                staleRequests.discardStaleRequestSession(request);
                throw unauthenticated();
            }
            Object persistedRecent = current.persisted().getAttribute(
                    IdentitySessionState.RECENT_ATTRIBUTE);
            IdentitySessionState.requireRecent(persistedRecent, userId, clock.instant(), policy);
            // A collision is deliberately indistinguishable from an issued request.
            if (identity.emailOccupied(candidate)) return;
            Instant now = clock.instant();
            identity.supersedeEmailChange(userId, now);
            identity.issueEmailChange(capabilityId, userId, candidate, digest,
                    now, now.plus(EMAIL_CHANGE_LIFETIME));
            delivery.queueCapability(capabilityId, envelope, now);
            identity.audit(userId, "email_change", "requested", now);
        });
    }

    void confirmEmailChange(UUID userId, String token, HttpServletRequest request,
            HttpServletResponse response) {
        final UUID capabilityId;
        try {
            capabilityId = EmailChangeToken.locator(token);
        } catch (IllegalArgumentException exception) {
            throw invalidChange();
        }
        var prepared = identity.emailChangeState(capabilityId, false).orElseThrow(
                CredentialManagementService::invalidChange);
        byte[] digest = EmailChangeToken.digest(token);
        if (!userId.equals(prepared.userId())
                || !MessageDigest.isEqual(prepared.digest(), digest)) throw invalidChange();
        UUID eventId = ids.generate();
        var oldRecipient = cipher.sealRecipient(eventId, "email_change_old_address",
                prepared.oldDisplayEmail());
        var newRecipient = cipher.sealRecipient(eventId, "email_change_new_address",
                prepared.candidateEmail());
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        String originalSessionId = requestSession.getId();
        boolean[] sessionMutationStarted = {false};
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.lockActiveAccount(userId)) throw unauthenticated();
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) {
                    staleRequests.discardStaleRequestSession(request);
                    throw unauthenticated();
                }
                Instant now = clock.instant();
                Object persistedRecent = current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE);
                IdentitySessionState.requireRecent(persistedRecent, userId, now, policy);
                var actual = identity.emailChangeState(capabilityId, true)
                        .orElseThrow(CredentialManagementService::invalidChange);
                if (!userId.equals(actual.userId()) || actual.consumedAt() != null
                        || actual.supersededAt() != null || actual.revokedAt() != null
                        || !actual.expiresAt().isAfter(now)
                        || !prepared.oldEmail().equals(actual.oldEmail())
                        || !prepared.oldDisplayEmail().equals(actual.oldDisplayEmail())
                        || !prepared.candidateEmail().equals(actual.candidateEmail())
                        || !MessageDigest.isEqual(actual.digest(), digest)
                        || identity.emailOccupied(actual.candidateEmail())) throw invalidChange();
                if (identity.consumeEmailChange(capabilityId, digest, now) != 1
                        || identity.changeEmail(userId, prepared.oldEmail(),
                                prepared.candidateEmail(), now) != 1) throw invalidChange();
                checkpoint.afterEmailMutation(request);
                sessions.revokeOthers(userId, current.primaryId());
                checkpoint.afterOtherSessionRevocation(request);
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                identity.auditEmailChange(userId, eventId, now);
                delivery.queueEmailChangeNotice(userId, eventId,
                        "email_change_old_address", oldRecipient, now);
                delivery.queueEmailChangeNotice(userId, eventId,
                        "email_change_new_address", newRecipient, now);
                checkpoint.afterEmailNoticeIntents(request);
            });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            if (failure instanceof DataIntegrityViolationException) throw invalidChange();
            throw failure;
        }
    }

    private static ApiFailureException invalidChange() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
    }

    void setPassword(UUID userId, String newPassword, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        IdentitySessionState.requireRecent(request, userId, clock.instant(), policy);
        IdentityInput.password(newPassword);
        // Argon2 must finish before any Account or Spring Session row lock.
        String verifier = passwords.encode(newPassword);
        String originalSessionId = requestSession.getId();
        boolean[] sessionMutationStarted = {false};
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.lockActiveAccount(userId)) throw unauthenticated();
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) {
                    staleRequests.discardStaleRequestSession(request);
                    throw unauthenticated();
                }
                Instant now = clock.instant();
                Object persistedRecent = current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE);
                IdentitySessionState.requireRecent(persistedRecent, userId, now, policy);
                if (identity.replacePassword(userId, verifier, now) != 1) {
                    throw new IllegalStateException("Locked Account could not change password");
                }
                checkpoint.afterPasswordMutation(request);
                sessions.revokeOthers(userId, current.primaryId());
                checkpoint.afterOtherSessionRevocation(request);
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                identity.audit(userId, "password_change", "completed", now);
            });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                // The database rolled back, but Java's request wrapper did not.
                // Detach it; never invalidate the original committed session row.
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            throw failure;
        }
    }

    void unlinkOidc(UUID userId, UUID linkId, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        String originalSessionId = requestSession.getId();
        boolean[] sessionMutationStarted = {false};
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.lockActiveAccount(userId)) throw unauthenticated();
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) {
                    staleRequests.discardStaleRequestSession(request);
                    throw unauthenticated();
                }
                Instant now = clock.instant();
                IdentitySessionState.requireRecent((Object) current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE), userId, now, policy);
                var link = oidcLinks.ownedActiveLinkForUpdate(userId, linkId)
                        .orElseThrow(() -> ApiFailureException.of(
                                ApiFailureException.Kind.RESOURCE_NOT_FOUND));
                if (!"https://accounts.google.com".equals(link.issuer())) {
                    throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
                }
                if (!oidcLinks.hasOtherUsableMethod(userId, linkId)) {
                    throw invalidChange();
                }
                if (!oidcLinks.revokeLink(userId, linkId, now)) throw invalidChange();
                checkpoint.afterOidcLinkMutation(request);
                sessions.revokeOthers(userId, current.primaryId());
                checkpoint.afterOtherSessionRevocation(request);
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                UUID eventId = ids.generate();
                identity.auditOidcLinkChange(userId, eventId, false, now);
                checkpoint.afterOidcLinkAudit(request);
                delivery.queueOidcNotice(userId, eventId, "google_oidc_unlinked", now);
                checkpoint.afterOidcLinkNotice(request);
            });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            try {
                identity.auditFailure(userId, "oidc_unlink", "denied", clock.instant());
            } catch (RuntimeException unavailable) {
                throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
            }
            throw failure;
        }
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
