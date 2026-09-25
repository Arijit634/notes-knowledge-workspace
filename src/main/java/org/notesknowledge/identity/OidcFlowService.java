package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@IdentityCoreEnabled
final class OidcFlowService {
    record Completion(boolean mfaRequired, String challengeId) { }

    private enum FailureReason {
        PROTOCOL_STATE_INVALID, PROTOCOL_TRANSACTION_EXPIRED,
        PROVIDER_VALIDATION_FAILED, PROVIDER_UNAVAILABLE,
        ACCOUNT_INELIGIBLE, ACCOUNT_ACTION_REQUIRED,
        RECENT_AUTH_IDENTITY_MISMATCH, RECENT_AUTH_STALE, INTERNAL_FAILURE
    }

    private static final Pattern HOSTED_DOMAIN = Pattern.compile(
            "(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final long OIDC_FUTURE_SKEW_SECONDS = 60;

    private final OidcProtocolPort protocol;
    private final GoogleOidcProperties policy;
    private final OidcSessionTransactionRepository transactionsRepository;
    private final OidcIdentityRepository links;
    private final IdentityPersistence identity;
    private final DatabaseUuidV7Generator ids;
    private final MfaChallengeService challenges;
    private final MfaSessionTransitions sessions;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final OidcRateControl rates;
    private final MfaProperties mfaPolicy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    OidcFlowService(OidcProtocolPort protocol, GoogleOidcProperties policy,
            OidcSessionTransactionRepository transactionsRepository, OidcIdentityRepository links,
            IdentityPersistence identity, DatabaseUuidV7Generator ids,
            MfaChallengeService challenges, MfaSessionTransitions sessions,
            IdentitySessionTransitionCheckpoint checkpoint, OidcRateControl rates,
            MfaProperties mfaPolicy, PlatformTransactionManager manager, Clock clock) {
        this.protocol = protocol;
        this.policy = policy;
        this.transactionsRepository = transactionsRepository;
        this.links = links;
        this.identity = identity;
        this.ids = ids;
        this.challenges = challenges;
        this.sessions = sessions;
        this.checkpoint = checkpoint;
        this.rates = rates;
        this.mfaPolicy = mfaPolicy;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    String begin(OidcProtocolPort.Action action, HttpServletRequest request) {
        UUID userId = action == OidcProtocolPort.Action.RECENT_AUTH
                ? IdentitySessionState.principal("ROLE_USER") : null;
        rates.check(action == OidcProtocolPort.Action.LOGIN ? "OIDC_LOGIN_START"
                : "OIDC_REAUTH_START", userId, request);
        FailureReason[] reason = {FailureReason.ACCOUNT_INELIGIBLE};
        try {
            if (userId != null && !identity.isActive(userId)) deny();
            // Discovery/metadata may be remote; it completes before authoritative session writes.
            reason[0] = FailureReason.PROVIDER_UNAVAILABLE;
            var authorization = protocol.begin(action);
            reason[0] = FailureReason.INTERNAL_FAILURE;
            HttpSession session = request.getSession();
            Instant now = clock.instant();
            session.setAttribute(OidcSessionTransaction.ATTRIBUTE,
                    new OidcSessionTransaction(action, authorization, session.getId(), userId,
                            now, now.plus(policy.transactionLifetime())));
            return authorization.getAuthorizationRequestUri();
        } catch (RuntimeException failure) {
            auditFailure(action, userId, reason[0]);
            throw failure;
        }
    }

    Completion complete(OidcProtocolPort.Action action, String returnedState,
            String returnedIssuer, String code,
            HttpServletRequest request, HttpServletResponse response) {
        UUID userId = action == OidcProtocolPort.Action.RECENT_AUTH
                ? IdentitySessionState.principal("ROLE_USER") : null;
        rates.check(action == OidcProtocolPort.Action.LOGIN ? "OIDC_LOGIN_CALLBACK"
                : "OIDC_REAUTH_CALLBACK", userId, request);
        FailureReason[] reason = {FailureReason.PROTOCOL_STATE_INVALID};
        try {
            return completeAttempt(action, returnedState, returnedIssuer, code,
                    request, response, userId, reason);
        } catch (RuntimeException failure) {
            auditFailure(action, userId, reason[0]);
            throw failure;
        }
    }

    private Completion completeAttempt(OidcProtocolPort.Action action, String returnedState,
            String returnedIssuer, String code, HttpServletRequest request,
            HttpServletResponse response, UUID userId, FailureReason[] reason) {
        if (code == null || code.isBlank() || code.length() > 4096
                || returnedState == null || returnedState.length() > 128) deny();
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) deny();
        Object pending = requestSession.getAttribute(OidcSessionTransaction.ATTRIBUTE);
        if (!(pending instanceof OidcSessionTransaction)) deny();
        OidcSessionTransaction initial = (OidcSessionTransaction) pending;
        if (initial.expiresAt() != null && !initial.expiresAt().isAfter(clock.instant())) {
            reason[0] = FailureReason.PROTOCOL_TRANSACTION_EXPIRED;
            deny();
        }
        if (!matches(initial, action, returnedState, requestSession.getId(), userId)) deny();
        // RFC 9207 authorization-response issuer is distinct from ID-token issuer validation.
        String[] issuerValues = request.getParameterValues("iss");
        if (issuerValues == null || issuerValues.length != 1 || returnedIssuer == null
                || returnedIssuer.length() > 255 || !policy.issuer().equals(returnedIssuer)) deny();

        // No database transaction or session-row lock spans provider discovery, JWK or code exchange.
        reason[0] = FailureReason.PROVIDER_VALIDATION_FAILED;
        OidcProtocolPort.ValidatedPrincipal principal;
        try {
            principal = protocol.verify(action, initial.authorization(), code, returnedState);
        } catch (ApiFailureException providerFailure) {
            if (providerFailure.kind() == ApiFailureException.Kind.SERVICE_UNAVAILABLE) {
                reason[0] = FailureReason.PROVIDER_UNAVAILABLE;
            }
            throw providerFailure;
        }
        if (principal == null || !policy.issuer().equals(principal.issuer())
                || principal.subject() == null
                || principal.subject().isBlank() || principal.subject().length() > 255) deny();
        if (action == OidcProtocolPort.Action.RECENT_AUTH) {
            reason[0] = FailureReason.RECENT_AUTH_STALE;
            Instant authTime = principal.authTime();
            if (!freshAuthTime(authTime, clock.instant())) deny();
        }
        reason[0] = FailureReason.INTERNAL_FAILURE;
        checkpoint.beforeOidcLock(request);
        boolean[] mutated = {false};
        try {
            Completion completed = transactions.execute(status -> {
                OidcSessionTransaction current = transactionsRepository.lockAndRead(
                        requestSession.getId(), action, userId);
                if (current == null || !matches(current, action, returnedState,
                        requestSession.getId(), userId)
                        || !current.authorization().equals(initial.authorization())) {
                    reason[0] = FailureReason.PROTOCOL_STATE_INVALID;
                    transactionsRepository.discardStale(request);
                    return null;
                }
                if (userId != null && !links.lockEligibleAccount(userId)) {
                    reason[0] = FailureReason.ACCOUNT_INELIGIBLE;
                    transactionsRepository.discardStale(request);
                    return null;
                }
                Instant now = clock.instant();
                if (action == OidcProtocolPort.Action.RECENT_AUTH) {
                    reason[0] = FailureReason.RECENT_AUTH_STALE;
                    if (!freshAuthTime(principal.authTime(), now)) deny();
                    reason[0] = FailureReason.RECENT_AUTH_IDENTITY_MISMATCH;
                    var link = links.linkForUpdate(principal.issuer(), principal.subject());
                    if (link.isEmpty() || !link.get().active()
                            || !userId.equals(link.get().userId())) deny();
                    // Only write request-local state after all principal checks succeed.
                    mutated[0] = true;
                    requestSession.removeAttribute(OidcSessionTransaction.ATTRIBUTE);
                    requestSession.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                            new IdentitySessionState.RecentAuthentication(userId, now, "oidc"));
                    identity.audit(userId, "oidc_recent_auth", "success", now);
                    checkpoint.afterSessionMutation(request);
                    return new Completion(false, null);
                }
                var link = links.linkForUpdate(principal.issuer(), principal.subject());
                UUID authenticatedUser;
                if (link.isPresent()) {
                    reason[0] = FailureReason.ACCOUNT_INELIGIBLE;
                    if (!link.get().active()) deny();
                    authenticatedUser = link.get().userId();
                    if (!links.markAuthenticatedIfEligible(authenticatedUser, now)) deny();
                } else {
                    reason[0] = FailureReason.ACCOUNT_ACTION_REQUIRED;
                    if (!googleAuthoritativeEmail(principal)) {
                        throw ApiFailureException.of(
                                ApiFailureException.Kind.OIDC_ACCOUNT_ACTION_REQUIRED);
                    }
                    String canonical;
                    try {
                        canonical = IdentityInput.canonicalEmail(principal.email());
                    } catch (ApiFailureException invalid) {
                        throw ApiFailureException.of(
                                ApiFailureException.Kind.OIDC_ACCOUNT_ACTION_REQUIRED);
                    }
                    authenticatedUser = ids.generate();
                    if (!links.createVerifiedAccount(authenticatedUser, canonical,
                            principal.email().trim(), now)) {
                        throw ApiFailureException.of(
                                ApiFailureException.Kind.OIDC_ACCOUNT_ACTION_REQUIRED);
                    }
                    if (!links.createLink(authenticatedUser, principal.issuer(),
                            principal.subject(), now)) {
                        throw ApiFailureException.of(
                                ApiFailureException.Kind.OIDC_ACCOUNT_ACTION_REQUIRED);
                    }
                    identity.audit(authenticatedUser, "oidc_bootstrap", "success", now);
                }
                identity.audit(authenticatedUser, "oidc_login", "success", now);
                boolean mfa = challenges.active(authenticatedUser);
                mutated[0] = true;
                requestSession.removeAttribute(OidcSessionTransaction.ATTRIBUTE);
                sessions.establish(authenticatedUser, !mfa, request, response);
                String challengeId = mfa ? challenges.begin(authenticatedUser, request, "oidc") : null;
                checkpoint.afterSessionMutation(request);
                return new Completion(mfa, challengeId);
            });
            if (completed == null) deny();
            return completed;
        } catch (RuntimeException failure) {
            if (mutated[0]) {
                try {
                    requestSession.invalidate();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
            throw failure;
        }
    }

    private boolean googleAuthoritativeEmail(OidcProtocolPort.ValidatedPrincipal principal) {
        if (!principal.emailVerified() || principal.email() == null) return false;
        String canonical;
        try {
            canonical = IdentityInput.canonicalEmail(principal.email());
        } catch (ApiFailureException invalid) {
            return false;
        }
        if (canonical.endsWith("@gmail.com")) return true;
        String hd = principal.hostedDomain();
        return hd != null && hd.length() <= 253 && HOSTED_DOMAIN.matcher(hd).matches();
    }

    private boolean freshAuthTime(Instant authTime, Instant now) {
        return authTime != null && authTime.isAfter(now.minus(mfaPolicy.recentAuthLifetime()))
                && !authTime.isAfter(now.plusSeconds(OIDC_FUTURE_SKEW_SECONDS));
    }

    private void auditFailure(OidcProtocolPort.Action action, UUID userId,
            FailureReason reason) {
        String category = action == OidcProtocolPort.Action.LOGIN
                ? "oidc_login" : "oidc_recent_auth";
        try {
            identity.auditFailure(userId, category, reason.name().toLowerCase(Locale.ROOT),
                    clock.instant());
        } catch (RuntimeException unavailable) {
            // Audit failure never converts a denied authentication into authority.
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    private boolean matches(OidcSessionTransaction transaction, OidcProtocolPort.Action action,
            String returnedState, String sessionId, UUID userId) {
        Instant now = clock.instant();
        String expected = transaction.authorization().getState();
        return transaction.action() == action && sessionId.equals(transaction.sessionId())
                && java.util.Objects.equals(transaction.userId(), userId)
                && transaction.issuedAt() != null && !transaction.issuedAt().isAfter(now)
                && transaction.expiresAt() != null && transaction.expiresAt().isAfter(now)
                && expected != null && expected.length() <= 128
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        returnedState.getBytes(StandardCharsets.US_ASCII));
    }

    private static void deny() {
        throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
