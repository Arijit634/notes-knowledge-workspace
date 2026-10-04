package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account -> persisted session -> Note. The Account lock serializes owner aggregate quota checks. */
@Service
class AttachmentTransactions {
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final NotesRepository notes;
    private final AttachmentRepository attachments;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    AttachmentTransactions(ObjectProvider<AccountEligibilityApi> eligibility, NotesRepository notes,
            AttachmentRepository attachments, DatabaseUuidV7Generator ids, Clock clock) {
        this.eligibility = eligibility; this.notes = notes; this.attachments = attachments; this.ids = ids; this.clock = clock;
    }
    @Transactional
    void preflight(UUID owner, UUID note, HttpServletRequest request) { authorize(owner, note, request, 0); }

    @Transactional
    AttachmentView accept(UUID owner, UUID note, HttpServletRequest request, String reference, String display,
            AttachmentMediaValidator.Validated media) {
        authorize(owner, note, request, media.sizeBytes());
        UUID id = ids.generate();
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        attachments.create(id, note, owner, reference, display, media, now);
        return new AttachmentView(id, note, media.kind(), display, media.mediaType(), media.sizeBytes(),
                media.width(), media.height(), media.durationSeconds(), media.pageCount(),
                "stored", "accepted", "retained", now, now);
    }
    private void authorize(UUID owner, UUID note, HttpServletRequest request, long bytes) {
        eligibility.getObject().requireCurrentOwner(owner, request);
        var current = notes.lock(owner, note).orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        if (!current.lifecycle().equals("active") && !current.lifecycle().equals("archived")) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        if (!attachments.fits(owner, note, bytes)) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
    }
}
