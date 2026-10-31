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
                    "publishing.media-storage.enabled")) {
                if (environment.getProperty(property, Boolean.class, false)) throw new IllegalStateException();
            }
            directory = Path.of(environment.getRequiredProperty("nkw.localhost.directory")).toAbsolutePath().normalize();
            if (!directory.equals(directory.toRealPath()) || !Files.isRegularFile(directory.resolve("LOCALHOST_INSTANCE"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(directory.resolve("LOCALHOST_INSTANCE")).equals("nkw-localhost-1\n")) throw new IllegalStateException();
            for (String name : java.util.List.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media")) {
                Path child = directory.resolve(name);
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || !child.equals(child.toRealPath())) throw new IllegalStateException();
            }
            if (environment.getProperty("knowledge.derivation.enabled", Boolean.class, false)
                    || environment.getProperty("nkw.localhost.synthetic-ai", Boolean.class, false)) requireSyntheticAi(environment);
        } catch (Exception invalid) { throw new IllegalStateException("Protected loopback localhost configuration required"); }
    }

    private void requireSyntheticAi(Environment environment) throws Exception {
        // Local transport opt-in never grants source/query authority. Knowledge still
        // checks the exact persisted source revision, owner, acknowledgement and manifest.
        if (!environment.getProperty("knowledge.derivation.enabled", Boolean.class, false)
                || !environment.getProperty("nkw.localhost.synthetic-ai", Boolean.class, false)
                || !"gemini".equals(environment.getProperty("knowledge.derivation.provider"))
                || !"unpaid".equals(environment.getProperty("knowledge.derivation.tier"))
                || !"global".equals(environment.getProperty("knowledge.derivation.region"))
                || !"gemini-embedding-2".equals(environment.getProperty("knowledge.derivation.embedding-model"))
                || !java.util.Set.of("gemini-3.6-flash","gemini-3.5-flash-lite").contains(environment.getProperty("knowledge.derivation.media-model", ""))
                || environment.getProperty("knowledge.derivation.video-soundtrack-model")!=null
                    && !java.util.Set.of("gemini-3.6-flash","gemini-3.5-flash-lite").contains(environment.getProperty("knowledge.derivation.video-soundtrack-model"))
                || !"unpaid-synthetic-demo".equals(environment.getProperty("knowledge.derivation.dispatch-policy"))) throw new IllegalStateException();
        String fingerprint=environment.getProperty("knowledge.derivation.approved-policy-fingerprint", "");
        if (!fingerprint.matches("[0-9a-f]{64}")
                || !fingerprint.equals(environment.getProperty("knowledge.processing-disclosure.approved-fingerprint"))
                || !"gemini".equals(environment.getProperty("knowledge.processing-disclosure.provider"))
                || !"unpaid".equals(environment.getProperty("knowledge.processing-disclosure.tier"))
                || !"global".equals(environment.getProperty("knowledge.processing-disclosure.region"))) throw new IllegalStateException();
        var binder=org.springframework.boot.context.properties.bind.Binder.get(environment);
        for (String field : java.util.List.of("approved-source-fingerprints", "approved-query-fingerprints")) {
            var approved=binder.bind("knowledge.derivation."+field,org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(java.util.List.of());
            int limit=field.equals("approved-source-fingerprints")?2048:256;
            boolean workspace=environment.getProperty("knowledge.derivation.synthetic-workspace-owner",java.util.UUID.class)!=null;
            if (approved.isEmpty()&&!workspace || approved.size()>limit || approved.stream().anyMatch(value->!value.matches("[0-9a-f]{64}"))) throw new IllegalStateException();
        }
        Path bundle=Path.of(environment.getRequiredProperty("knowledge.processing-disclosure.path")).toAbsolutePath().normalize();
        Path approvedDirectory=directory.resolve("synthetic-ai");
        if (!Files.isDirectory(approvedDirectory,LinkOption.NOFOLLOW_LINKS) || !approvedDirectory.equals(approvedDirectory.toRealPath())
                || !bundle.equals(approvedDirectory.resolve("disclosure.json")) || !Files.isRegularFile(bundle,LinkOption.NOFOLLOW_LINKS)
                || !bundle.equals(bundle.toRealPath())) throw new IllegalStateException();
    }

    public Path directory(String name) {
        if (!java.util.Set.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media").contains(name))
            throw new IllegalArgumentException("Invalid localhost storage category");
        return directory.resolve(name);
    }
}
