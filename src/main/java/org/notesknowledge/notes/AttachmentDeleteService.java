package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Logical denial commits before best-effort physical custody cleanup. */
@Service
class AttachmentDeleteService {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentDeleteService.class);
    private final AttachmentDeleteTransactions transactions;
    private final AttachmentRepository attachments;
    private final PrivateAttachmentObjectStore store;

    AttachmentDeleteService(AttachmentDeleteTransactions transactions, AttachmentRepository attachments, PrivateAttachmentObjectStore store) {
        this.transactions = transactions; this.attachments = attachments; this.store = store;
    }

    @Transactional(propagation = Propagation.NEVER)
    void delete(UUID owner, UUID note, UUID attachment, String ifMatch, HttpServletRequest request) {
        cleanup(transactions.remove(owner, note, attachment, ifMatch, request));
    }

    @Transactional(propagation = Propagation.NEVER)
    void reconcilePending() {
        for (var target : attachments.pendingCleanup()) cleanup(target);
    }

    private void cleanup(AttachmentCleanupTarget target) {
        if (target.reference() == null || !target.reference().matches("private-attachment/[0-9a-f]{64}")) {
            LOG.warn("attachment_cleanup_deferred");
            return;
        }
        try { store.delete(target.reference()); }
        catch (RuntimeException failure) {
            // The retained -> pending commit already denied every future private read.
            LOG.warn("attachment_cleanup_deferred");
            return;
        }
        try { transactions.finalizeCleanup(target); }
        catch (RuntimeException failure) { LOG.warn("attachment_cleanup_metadata_deferred"); }
    }
}
