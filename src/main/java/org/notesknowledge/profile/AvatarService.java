package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.notesknowledge.websupport.ApiFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Coordinates private byte custody outside short authoritative transactions. */
@Service
class AvatarService {
    private static final Logger LOG = LoggerFactory.getLogger(AvatarService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration ORPHAN_AGE = Duration.ofHours(24);
    private static final int RECONCILE_BATCH = 100;
    private final Semaphore uploads = new Semaphore(2);
    // Initial deployment is one replica. This lock protects in-flight staged
    // objects from reconciliation, not authorization or durable job authority.
    private final ReentrantReadWriteLock staging = new ReentrantReadWriteLock(true);
    private final AvatarValidator validator;
    private final AvatarObjectStore store;
    private final AvatarTransactions transactions;
    private final AvatarAssetRepository assets;
    private final Clock clock;

    AvatarService(AvatarValidator validator, AvatarObjectStore store, AvatarTransactions transactions,
            AvatarAssetRepository assets, Clock clock) {
        this.validator = validator; this.store = store; this.transactions = transactions; this.assets = assets; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    ProfileView replace(UUID owner, HttpServletRequest request, MultipartFile file) {
        if (!uploads.tryAcquire()) throw unavailable();
        staging.readLock().lock();
        String reference = null;
        boolean committed = false;
        try {
            AvatarValidator.Canonical image;
            try (var input = file.getInputStream()) { image = validator.validate(input, file.getOriginalFilename()); }
            byte[] random = new byte[32]; RANDOM.nextBytes(random);
            reference = "private-avatar/" + HexFormat.of().formatHex(random);
            try { store.write(reference, image.bytes()); } catch (RuntimeException failure) { throw unavailable(); }
            var swap = transactions.replace(owner, request, reference, image);
            committed = true;
            clean(swap.retired());
            return swap.view();
        } catch (IOException failure) { throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT); }
        finally {
            if (reference != null && !committed) deleteBestEffort(reference);
            staging.readLock().unlock(); uploads.release();
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    void remove(UUID owner, HttpServletRequest request) { clean(transactions.remove(owner, request)); }

    private void clean(AvatarAsset asset) {
        if (asset == null) return;
        if (deleteBestEffort(asset.reference())) {
            try { assets.cleaned(asset, clock.instant()); }
            catch (RuntimeException failure) { LOG.warn("avatar_cleanup_metadata_deferred"); }
        }
    }

    private boolean deleteBestEffort(String reference) {
        try { store.delete(reference); return true; }
        catch (RuntimeException failure) { LOG.warn("avatar_cleanup_deferred"); return false; }
    }

    /** Bounded internal operational entry point, not an HTTP endpoint or scheduler.
     * Caller carries the last reference into the next bounded inventory page.
     */
    @Transactional(propagation = Propagation.NEVER)
    String reconcile(String afterReference) {
        staging.writeLock().lock();
        try {
            for (var asset : assets.pending(RECONCILE_BATCH)) clean(asset);
            var cutoff = clock.instant().minus(ORPHAN_AGE);
            var inventory = store.inventoryBefore(cutoff, afterReference, RECONCILE_BATCH);
            if (inventory.size() > RECONCILE_BATCH) throw unavailable();
            String cursor = afterReference;
            for (var object : inventory) {
                String reference = object.reference();
                if (reference == null || !reference.matches("private-avatar/[0-9a-f]{64}")
                        || object.createdAt() == null || !object.createdAt().isBefore(cutoff)
                        || cursor != null && reference.compareTo(cursor) <= 0) throw unavailable();
                if (!assets.referenced(reference)) deleteBestEffort(reference);
                cursor = reference;
            }
            return inventory.size() == RECONCILE_BATCH ? cursor : null;
        } finally { staging.writeLock().unlock(); }
    }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
