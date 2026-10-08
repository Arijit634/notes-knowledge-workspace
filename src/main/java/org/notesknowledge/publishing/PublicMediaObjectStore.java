package org.notesknowledge.publishing;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** Private storage namespace for independent public copies, never an anonymous storage URL.
 * Create-only writes consume exactly sizeBytes with bounded I/O timeouts. A missing object
 * returns null on openRange. Delete is idempotent. Inventory is bounded, exclusive and lexical.
 */
interface PublicMediaObjectStore {
    void write(String generatedReference,InputStream source,long sizeBytes);
    InputStream openRange(String generatedReference,long offset,long length);
    void delete(String generatedReference);
    List<StoredObject> inventoryBefore(Instant cutoff,String afterReference,int limit);
    record StoredObject(String reference,Instant createdAt) {
        @Override public String toString(){return "StoredPublicMedia[REDACTED]";}
    }
}
