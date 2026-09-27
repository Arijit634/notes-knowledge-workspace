package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reconciles the safe projection from authoritative, owner-checked framework rows. */
@Service
@IdentityCoreEnabled
final class SessionDescriptorService {
    private final SpringSessionAuthorityAdapter sessions;
    private final ApplicationSessionDescriptorRepository descriptors;

    SessionDescriptorService(SpringSessionAuthorityAdapter sessions,
            ApplicationSessionDescriptorRepository descriptors) {
        this.sessions = sessions;
        this.descriptors = descriptors;
    }

    void recordEstablished(UUID userId, HttpServletRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Full session establishment requires Identity transaction");
        }
        var requestSession = request.getSession(false);
        var current = requestSession == null ? null : sessions.bySessionId(requestSession.getId());
        if (current == null) throw new IllegalStateException("Full session framework row unavailable");
        descriptors.recordFull(current.primaryId(), userId,
                SessionClientLabel.from(request.getHeader("User-Agent")),
                current.created(), current.seen(), current.expiry());
    }

    List<ApplicationSessionDescriptorRepository.Descriptor> active(UUID userId) {
        var active = new ArrayList<ApplicationSessionDescriptorRepository.Descriptor>();
        for (var row : sessions.activeFull(userId)) {
            // Lock/re-read exact framework authority before projecting. A concurrent
            // revoke cannot turn a stale candidate into a displayed active handle.
            var locked = sessions.lockFull(userId, row.primaryId());
            if (locked == null) continue;
            var prior = descriptors.find(userId, locked.primaryId());
            if (prior == null) {
                descriptors.recordFull(locked.primaryId(), userId, "Existing session",
                        locked.created(), locked.seen(), locked.expiry());
            } else if (prior.revokedAt() == null) {
                descriptors.recordFull(locked.primaryId(), userId, prior.client(),
                        locked.created(), locked.seen(), locked.expiry());
            } else {
                continue;
            }
            active.add(descriptors.find(userId, locked.primaryId()));
        }
        return active;
    }

    ApplicationSessionDescriptorRepository.Descriptor current(UUID userId, String primaryId) {
        var descriptor = descriptors.find(userId, primaryId);
        if (descriptor == null || descriptor.revokedAt() != null) {
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        return descriptor;
    }
}
