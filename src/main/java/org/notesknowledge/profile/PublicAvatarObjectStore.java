package org.notesknowledge.profile;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Private storage for independently copied public disclosure. References never authorize bytes.
 * Writes are create-only, reads and writes have bounded I/O timeouts, missing reads return null,
 * deletion is idempotent, and inventory is exclusive/lexical and limited by the caller's bound.
 */
interface PublicAvatarObjectStore {
    void write(String generatedReference, byte[] canonicalBytes);
    InputStream open(String generatedReference,long expectedSize);
    void delete(String generatedReference);
    List<StoredObject> inventoryBefore(Instant cutoff,String afterReference,int limit);
    record StoredObject(String reference,Instant createdAt) {
        @Override public String toString(){return "PublicAvatarObject[REDACTED]";}
    }
}
