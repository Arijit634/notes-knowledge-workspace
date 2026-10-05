package org.notesknowledge.notes;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Notes-only private, create-only storage. References never authorize bytes.
 * Writes consume exactly the bounded input, use bounded I/O timeouts, and must
 * never publish bytes. Delete is idempotent. Inventory is strictly lexicographic
 * by reference, exclusive afterReference, filtered createdAt < cutoff, at most limit.
 * Storage stays separate from parser temporary custody and public/avatar storage.
 */
interface PrivateAttachmentObjectStore {
    /** Open at offset, with positive length bounded by the authorized representation.
     * Callers consume at most length bytes and always close the stream. No DB transaction.
     */
    InputStream openRange(String generatedReference, long offset, long length);
    void write(String generatedReference, InputStream source, long sizeBytes);
    void delete(String generatedReference);
    List<StoredObject> inventoryBefore(Instant cutoff, String afterReference, int limit);

    record StoredObject(String reference, Instant createdAt) {
        @Override public String toString() { return "StoredAttachment[REDACTED]"; }
    }
}
