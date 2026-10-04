package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Byte custody never grants authority. Provider/parser I/O never holds a DB transaction. */
@Service
class AttachmentUploadService {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentUploadService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration ORPHAN_AGE = Duration.ofHours(24);
    private static final int INVENTORY_LIMIT = 100;
    private final Semaphore admission = new Semaphore(2);
    // Single-replica in-flight exclusion only, not persistence/authorization authority.
    private final ReentrantReadWriteLock staging = new ReentrantReadWriteLock(true);
    private final AttachmentTransactions transactions;
    private final AttachmentRepository repository;
    private final PrivateAttachmentObjectStore store;
    private final AttachmentMediaValidator validator;
    private final RateControlService rates;
    private final RateKeyDeriver keys;
    private final Clock clock;

    AttachmentUploadService(AttachmentTransactions transactions, AttachmentRepository repository,
            PrivateAttachmentObjectStore store, AttachmentMediaValidator validator, RateControlService rates,
            RateKeyDeriver keys, Clock clock) {
        this.transactions = transactions; this.repository = repository; this.store = store;
        this.validator = validator; this.rates = rates; this.keys = keys; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    AttachmentView upload(UUID owner, UUID note, HttpServletRequest request, MultipartFile file) {
        transactions.preflight(owner, note, request);
        rate("ATTACHMENT_UPLOAD", "subject:" + owner);
        rate("ATTACHMENT_UPLOAD", "source:" + request.getRemoteAddr());
        rate("ATTACHMENT_UPLOAD_GLOBAL", "whole-deployment");
        String filename = displayFilename(file.getOriginalFilename());
        if (file.getSize() > AttachmentMediaValidator.VIDEO_BYTES) throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        if (!admission.tryAcquire()) throw ApiFailureException.rateLimited(1);
        staging.readLock().lock();
        java.nio.file.Path custody = null;
        String sourceReference = null, acceptedReference = null;
        boolean committed = false;
        try {
            custody = AttachmentParserRuntime.privateDirectory();
            var input = custody.resolve("input.bin");
            long sourceSize = spool(file, input);
            sourceReference = reference();
            write(sourceReference, input, sourceSize);
            var media = validator.validate(input, filename, file.getContentType());
            acceptedReference = sourceReference;
            if (media.kind().equals("image")) {
                // Create-only canonical output; the source object remains unreachable and
                // is removed. No replacement/promotion can affect already-authorized bytes.
                acceptedReference = reference();
                write(acceptedReference, input, media.sizeBytes());
            }
            var view = transactions.accept(owner, note, request, acceptedReference, filename, media);
            committed = true; // The transactional proxy has committed before control returns.
            return view;
        } catch (IOException failure) { throw unavailable(); }
        finally {
            if (sourceReference != null && (!committed || !sourceReference.equals(acceptedReference))) cleanUnreferenced(sourceReference);
            if (!committed && acceptedReference != null && !acceptedReference.equals(sourceReference)) cleanUnreferenced(acceptedReference);
            if (custody != null) AttachmentParserRuntime.removeDirectory(custody);
            staging.readLock().unlock(); admission.release();
        }
    }

    private long spool(MultipartFile file, java.nio.file.Path output) throws IOException {
        try (var source = file.getInputStream(); var destination = java.nio.file.Files.newOutputStream(output)) {
            byte[] prefix = source.readNBytes(12);
            int limit = sourceLimit(prefix);
            destination.write(prefix);
            long count = prefix.length;
            byte[] buffer = new byte[8192];
            for (;;) {
                int read = source.read(buffer, 0, (int)Math.min(buffer.length, limit - count + 1));
                if (read < 0) break;
                if (read == 0) throw unavailable();
                count += read;
                if (count > limit) throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
                destination.write(buffer, 0, read);
            }
            return count;
        }
    }

    private static int sourceLimit(byte[] prefix) {
        if (prefix.length >= 3 && (prefix[0] == (byte)255 && prefix[1] == (byte)216 && prefix[2] == (byte)255
                || prefix.length >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(prefix, 8),
                        new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10}))) return AttachmentMediaValidator.IMAGE_BYTES;
        if (prefix.length >= 5 && new String(prefix, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return AttachmentMediaValidator.PDF_BYTES;
        if (prefix.length >= 12 && new String(prefix, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")) return AttachmentMediaValidator.AUDIO_BYTES;
        return AttachmentMediaValidator.VIDEO_BYTES;
    }

    private void write(String reference, java.nio.file.Path input, long size) throws IOException {
        try (var stream = java.nio.file.Files.newInputStream(input)) { store.write(reference, stream, size); }
        catch (RuntimeException failure) { throw unavailable(); }
    }
    private void rate(String control, String material) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control), keys.derive(control, material), 1),
                RateControlService.Policy.SECURITY_CRITICAL);
    }
    private static String reference() {
        byte[] entropy = new byte[32]; RANDOM.nextBytes(entropy);
        return "private-attachment/" + HexFormat.of().formatHex(entropy);
    }
    private static String displayFilename(String input) {
        String value = input == null || input.isBlank() ? "attachment" : input;
        if (value.codePointCount(0, value.length()) > 255 || value.codePoints().anyMatch(
                c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        return value.replace('/', '_').replace('\\', '_');
    }
    private void cleanUnreferenced(String reference) {
        try {
            // A failed/uncertain commit is not permission to delete possibly committed
            // bytes. If DB currentness cannot be established, defer to reconciliation.
            if (!repository.referenced(reference)) store.delete(reference);
        } catch (RuntimeException failure) { LOG.warn("attachment_cleanup_deferred"); }
    }

    /** Internal bounded operation only. No scheduler or HTTP mapping. */
    @Transactional(propagation = Propagation.NEVER)
    String reconcile(String afterReference) {
        if (afterReference != null && !afterReference.matches("private-attachment/[0-9a-f]{64}")) throw unavailable();
        staging.writeLock().lock();
        try {
            var cutoff = clock.instant().minus(ORPHAN_AGE);
            var page = store.inventoryBefore(cutoff, afterReference, INVENTORY_LIMIT);
            if (page.size() > INVENTORY_LIMIT) throw unavailable();
            String cursor = afterReference;
            for (var object : page) {
                String reference = object.reference();
                if (reference == null || !reference.matches("private-attachment/[0-9a-f]{64}")
                        || object.createdAt() == null || !object.createdAt().isBefore(cutoff)
                        || cursor != null && reference.compareTo(cursor) <= 0) throw unavailable();
                cleanUnreferenced(reference); cursor = reference;
            }
            return page.size() == INVENTORY_LIMIT ? cursor : null;
        } finally { staging.writeLock().unlock(); }
    }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
