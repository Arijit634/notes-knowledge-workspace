package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AttachmentDeleteTransactions {
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final NotesRepository notes;
    private final AttachmentRepository attachments;
    private final IfMatchPrecondition preconditions;
    private final StrongCoreEtagCodec etags;
    private final Clock clock;
    private final org.notesknowledge.knowledge.KnowledgeInvalidationApi invalidation;

    AttachmentDeleteTransactions(ObjectProvider<AccountEligibilityApi> eligibility, NotesRepository notes,
            AttachmentRepository attachments, IfMatchPrecondition preconditions, StrongCoreEtagCodec etags, Clock clock,
            org.notesknowledge.knowledge.KnowledgeInvalidationApi invalidation) {
        this.eligibility = eligibility; this.notes = notes; this.attachments = attachments;
        this.preconditions = preconditions; this.etags = etags; this.clock = clock;
        this.invalidation=invalidation;
    }

    @Transactional
    @org.notesknowledge.CoordinatedMutation
    AttachmentCleanupTarget remove(UUID owner, UUID note, UUID attachment, String ifMatch, HttpServletRequest request) {
        var guard = eligibility.getIfAvailable();
        if (guard == null) throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        // Account/current session -> Note -> Attachment. Preconditions cannot become an existence oracle.
        guard.requireCurrentOwner(owner, request);
        notes.lock(owner, note).orElseThrow(AttachmentDeleteTransactions::missing);
        var current = attachments.lockRetained(owner, note, attachment).orElseThrow(AttachmentDeleteTransactions::missing);
        preconditions.requireCurrent(ifMatch, etags.encode(new AttachmentCoreVersion(attachment, current.revision())));
        if (attachments.remove(owner, note, attachment, current.revision(), clock.instant().truncatedTo(ChronoUnit.MILLIS)) != 1) {
            throw missing();
        }
        invalidation.attachmentChanged(owner,note,attachment);
        return new AttachmentCleanupTarget(attachment, current.reference(), Math.incrementExact(current.revision()));
    }

    @Transactional
    void finalizeCleanup(AttachmentCleanupTarget target) {
        // Re-read under lock: a concurrent reconciler may already have finalized it.
        if (attachments.lockPending(target).isPresent()) {
            attachments.finalizeCleanup(target, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        }
    }

    private static ApiFailureException missing() { return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND); }
}
