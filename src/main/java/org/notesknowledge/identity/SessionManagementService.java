package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-scoped session commands. Spring Session, not the descriptor, remains authority. */
@Service
@IdentityCoreEnabled
final class SessionManagementService {
    record SessionView(String sessionHandle, boolean current, String client,
            Instant createdAt, Instant lastSeenAt, Instant expiresAt) { }
    record SessionList(List<SessionView> sessions) { }

    private final IdentityPersistence identity;
    private final SpringSessionAuthorityAdapter sessions;
    private final SessionDescriptorService descriptorService;
    private final ApplicationSessionDescriptorRepository descriptors;
    private final SessionHandleCodec handles;
    private final MfaSessionChallengeRepository requestSessions;
    private final MfaProperties policy;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final Clock clock;
    private final TransactionTemplate transactions;

    SessionManagementService(IdentityPersistence identity, SpringSessionAuthorityAdapter sessions,
            SessionDescriptorService descriptorService,
            ApplicationSessionDescriptorRepository descriptors, SessionHandleCodec handles,
            MfaSessionChallengeRepository requestSessions, MfaProperties policy, Clock clock,
            IdentitySessionTransitionCheckpoint checkpoint, PlatformTransactionManager manager) {
        this.identity = identity;
        this.sessions = sessions;
        this.descriptorService = descriptorService;
        this.descriptors = descriptors;
        this.handles = handles;
        this.requestSessions = requestSessions;
        this.policy = policy;
        this.checkpoint = checkpoint;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    SessionList list(UUID userId, HttpServletRequest request) {
        return transactions.execute(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var requestSession = request.getSession(false);
            if (requestSession == null) throw unauthenticated();
            var current = sessions.bySessionId(requestSession.getId());
            if (current == null) throw unauthenticated();
            var live = descriptorService.active(userId);
            Set<String> primaryIds = live.stream()
                    .map(ApplicationSessionDescriptorRepository.Descriptor::primaryId)
                    .collect(Collectors.toSet());
            if (!primaryIds.contains(current.primaryId())) throw unauthenticated();
            // Reconcile pre-migration full sessions only after checking persisted authority.
            var result = live.stream()
                    .sorted(Comparator.comparing(
                                    (ApplicationSessionDescriptorRepository.Descriptor d) ->
                                            d.primaryId().equals(current.primaryId())).reversed()
                            .thenComparing(ApplicationSessionDescriptorRepository.Descriptor::lastSeenAt,
                                    Comparator.reverseOrder())
                            .thenComparing(ApplicationSessionDescriptorRepository.Descriptor::primaryId))
                    .limit(100)
                    .map(d -> new SessionView(handles.encode(d.primaryId(), userId),
                            d.primaryId().equals(current.primaryId()), d.client(),
                            d.createdAt(), d.lastSeenAt(), d.expiresAt()))
                    .toList();
            return new SessionList(result);
        });
    }

    void revokeOne(UUID userId, String handle, HttpServletRequest request) {
        String currentId = currentId(request);
        boolean[] revokeCurrent = {false};
        transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var current = requireCurrent(userId, currentId);
            var location = handles.decode(handle, userId);
            var descriptor = descriptorService.current(userId, location.primaryId());
            var target = sessions.lockFull(userId, descriptor.primaryId());
            if (target == null) throw notFound();
            requireRecent(current, userId);
            revokeCurrent[0] = target.primaryId().equals(current.primaryId());
            sessions.deleteLocked(userId, target);
            identity.auditSessionRevocation(userId, "one", clock.instant());
            if (!revokeCurrent[0]) sessions.consumeRecent(current);
        });
        if (revokeCurrent[0]) detach(request);
        else request.getSession(false).removeAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
    }

    void revokeOthers(UUID userId, HttpServletRequest request) {
        String currentId = currentId(request);
        transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var current = requireCurrent(userId, currentId);
            requireRecent(current, userId);
            checkpoint.afterSessionManagementLock(request);
            sessions.revokeOthers(userId, current.primaryId());
            sessions.consumeRecent(current);
            identity.auditSessionRevocation(userId, "others", clock.instant());
        });
        request.getSession(false).removeAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
    }

    void revokeAll(UUID userId, HttpServletRequest request) {
        String currentId = currentId(request);
        transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)) throw unauthenticated();
            var current = requireCurrent(userId, currentId);
            requireRecent(current, userId);
            checkpoint.afterSessionManagementLock(request);
            sessions.revokeAll(userId);
            identity.auditSessionRevocation(userId, "all", clock.instant());
        });
        detach(request);
    }

    private SpringSessionAuthorityAdapter.CurrentSession requireCurrent(UUID userId,
            String currentId) {
        var current = sessions.lockCurrent(userId, currentId);
        if (current == null) throw unauthenticated();
        return current;
    }

    private void requireRecent(SpringSessionAuthorityAdapter.CurrentSession current, UUID userId) {
        IdentitySessionState.requireRecent((Object) current.persisted().getAttribute(
                IdentitySessionState.RECENT_ATTRIBUTE), userId, clock.instant(), policy);
    }

    private void detach(HttpServletRequest request) {
        requestSessions.discardStaleRequestSession(request);
        SecurityContextHolder.clearContext();
    }

    private static String currentId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw unauthenticated();
        return session.getId();
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }

    private static ApiFailureException notFound() {
        return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
    }
}
