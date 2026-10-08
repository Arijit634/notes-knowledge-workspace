package org.notesknowledge.profile;

import java.time.Instant;
import java.util.List;

/** Profile-specific private storage capability. References never grant authority.
 * Implementations must enforce bounded timeouts, create-only writes, private
 * storage, idempotent deletion, and bounded oldest-first inventory pagination.
 */
interface AvatarObjectStore {
    /** Internal bounded canonical-source copy read only; never anonymous authority. */
    default java.io.InputStream open(String generatedReference, long expectedSize) {
        throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
    void write(String generatedReference, byte[] canonicalBytes);
    void delete(String generatedReference);
    List<StoredObject> inventoryBefore(Instant cutoff, String afterReference, int limit);

    record StoredObject(String reference, Instant createdAt) {
        @Override public String toString() { return "StoredAvatar[REDACTED]"; }
    }
}
