package org.notesknowledge.profile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.LocalhostMode;
import org.notesknowledge.LocalhostObjectDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("localhost")
class LocalhostAvatarStorage {
    @Bean AvatarObjectStore localPrivateAvatar(LocalhostMode mode) {
        var objects = new LocalhostObjectDirectory(mode, "private-avatar");
        return new AvatarObjectStore() {
            public void write(String ref, byte[] bytes) { objects.write(ref, new ByteArrayInputStream(bytes), bytes.length); }
            public InputStream open(String ref, long size) { return objects.open(ref, 0, size); }
            public void delete(String ref) { objects.delete(ref); }
            public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) {
                return objects.inventory(cutoff, after, limit).stream().map(e -> new StoredObject(e.reference(), e.createdAt())).toList();
            }
        };
    }
    @Bean PublicAvatarObjectStore localPublicAvatar(LocalhostMode mode) {
        var objects = new LocalhostObjectDirectory(mode, "public-profile-avatar");
        return new PublicAvatarObjectStore() {
            public void write(String ref, byte[] bytes) { objects.write(ref, new ByteArrayInputStream(bytes), bytes.length); }
            public InputStream open(String ref, long size) { return objects.open(ref, 0, size); }
            public void delete(String ref) { objects.delete(ref); }
            public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) {
                return objects.inventory(cutoff, after, limit).stream().map(e -> new StoredObject(e.reference(), e.createdAt())).toList();
            }
        };
    }
}
