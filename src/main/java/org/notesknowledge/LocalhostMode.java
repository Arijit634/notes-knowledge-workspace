package org.notesknowledge;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit development transport boundary, never selected by normal runtime defaults. */
@Component
@Profile("localhost")
public final class LocalhostMode {
    private final Path directory;

    public LocalhostMode(Environment environment) {
        try {
            if (!environment.matchesProfiles("operations")
                    || !"127.0.0.1".equals(environment.getProperty("server.address"))
                    || !environment.getProperty("server.ssl.enabled", Boolean.class, false)
                    || !"jdbc:postgresql://127.0.0.1:55438/nkw_owner_local".equals(environment.getProperty("spring.datasource.url"))
                    || !"nkw_local_runtime".equals(environment.getProperty("spring.datasource.username"))) throw new IllegalStateException();
            for (String property : java.util.List.of("identity.delivery.smtp-enabled", "identity.oidc.google.enabled",
                    "notes.private-storage.enabled", "profile.avatar-storage.enabled", "profile.public-avatar-storage.enabled",
                    "publishing.media-storage.enabled", "knowledge.derivation.enabled")) {
                if (environment.getProperty(property, Boolean.class, false)) throw new IllegalStateException();
            }
            directory = Path.of(environment.getRequiredProperty("nkw.localhost.directory")).toAbsolutePath().normalize();
            if (!directory.equals(directory.toRealPath()) || !Files.isRegularFile(directory.resolve("LOCALHOST_INSTANCE"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(directory.resolve("LOCALHOST_INSTANCE")).equals("nkw-localhost-1\n")) throw new IllegalStateException();
            for (String name : java.util.List.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media")) {
                Path child = directory.resolve(name);
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || !child.equals(child.toRealPath())) throw new IllegalStateException();
            }
        } catch (Exception invalid) { throw new IllegalStateException("Protected loopback localhost configuration required"); }
    }

    public Path directory(String name) {
        if (!java.util.Set.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media").contains(name))
            throw new IllegalArgumentException("Invalid localhost storage category");
        return directory.resolve(name);
    }
}
