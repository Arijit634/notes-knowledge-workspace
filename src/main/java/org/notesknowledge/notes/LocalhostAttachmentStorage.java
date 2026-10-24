package org.notesknowledge.notes;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.LocalhostMode;
import org.notesknowledge.LocalhostObjectDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("localhost")
final class LocalhostAttachmentStorage implements PrivateAttachmentObjectStore {
    private final LocalhostObjectDirectory objects;
    LocalhostAttachmentStorage(LocalhostMode mode) { objects = new LocalhostObjectDirectory(mode, "private-attachment"); }
    public void write(String reference, InputStream source, long size) { objects.write(reference, source, size); }
    public InputStream openRange(String reference, long offset, long length) { return objects.open(reference, offset, length); }
    public void delete(String reference) { objects.delete(reference); }
    public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) {
        return objects.inventory(cutoff, after, limit).stream().map(e -> new StoredObject(e.reference(), e.createdAt())).toList();
    }
}
