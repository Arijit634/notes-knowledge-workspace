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

    /** A bounded provider page can contain only too-new objects. Its scanned
     * cursor must still advance; matching-count alone cannot prove end-of-sweep. */
    default InventoryPage scanBefore(Instant cutoff,String afterReference,int limit) {
        var objects=inventoryBefore(cutoff,afterReference,limit);
        return new InventoryPage(objects,objects.size()==limit?objects.getLast().reference():null);
    }
    record InventoryPage(List<StoredObject> objects,String continueAfter) {
        public InventoryPage { objects=List.copyOf(objects); }
        @Override public String toString(){return "AttachmentInventoryPage[REDACTED]";}
    }

    record StoredObject(String reference, Instant createdAt) {
        @Override public String toString() { return "StoredAttachment[REDACTED]"; }
    }
}
