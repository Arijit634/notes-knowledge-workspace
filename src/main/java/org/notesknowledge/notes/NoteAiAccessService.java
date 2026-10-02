package org.notesknowledge.notes;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class NoteAiAccessService {
    static final int BATCH_SIZE = 100;
    private final NotesRepository repository;
    private final NotesService notes;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final Clock clock;
    private final RateControlService rates;
    private final RateKeyDeriver keys;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    NoteAiAccessService(NotesRepository repository, NotesService notes, StrongCoreEtagCodec etags,
            IfMatchPrecondition preconditions, Clock clock, RateControlService rates,
            RateKeyDeriver keys, ObjectProvider<PlatformTransactionManager> transactions) {
        this.repository = repository;
        this.notes = notes;
        this.etags = etags;
        this.preconditions = preconditions;
        this.clock = clock;
        this.rates = rates;
        this.keys = keys;
        this.transactions = transactions;
    }

    @Transactional
    NotesService.EtaggedNote set(UUID owner, UUID id, String ifMatch, boolean enabled) {
        var current = repository.lock(owner, id).orElseThrow(() ->
                ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch, etags.encode(new NoteCoreVersion(id, current.revision())));
        if (current.aiEnabled() != enabled) {
            repository.setAiAccess(owner, java.util.List.of(id), enabled,
                    clock.instant().truncatedTo(ChronoUnit.MILLIS));
        }
        return notes.get(owner, id);
    }

    long bulk(UUID owner, NoteAiAccessController.BulkRequest request, String source) {
        var batches = new TransactionTemplate(transactions.getObject());
        batches.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Bound the traversal to existing IDs at acceptance, not an ever-growing corpus.
        requireRate(owner, source);
        UUID upper = repository.aiAccessUpperBound(owner).orElse(null);
        if (upper == null) return 0;
        UUID after = null;
        long affected = 0;
        while (true) {
            UUID continuation = after;
            // No Redis/network operation is made while a Note transaction is open.
            Batch result = batches.execute(status -> {
                var ids = repository.lockAiAccessBatch(owner, request.aiEnabled(), request.noteIds(),
                        continuation, upper, BATCH_SIZE);
                if (ids.isEmpty()) return new Batch(null, 0, 0);
                int changed = repository.setAiAccess(owner, ids, request.aiEnabled(),
                        clock.instant().truncatedTo(ChronoUnit.MILLIS));
                return new Batch(ids.getLast(), ids.size(), changed);
            });
            affected += result.changed();
            if (result.selected() < BATCH_SIZE) return affected;
            after = result.last();
            // A later failure returns an error, never a misleading success summary.
            // Prior committed batches remain deliberate changes; a retry is idempotent.
            requireRate(owner, source);
        }
    }

    private void requireRate(UUID owner, String source) {
        require("NOTE_AI_BULK", "subject:" + owner);
        require("NOTE_AI_BULK", "source:" + source);
        require("NOTE_AI_BULK_GLOBAL", "whole-deployment");
    }

    private void require(String control, String material) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                keys.derive(control, material), 1), RateControlService.Policy.SECURITY_CRITICAL);
    }

    private record Batch(UUID last, int selected, int changed) { }
}
