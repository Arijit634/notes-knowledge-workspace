package org.notesknowledge;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Real Google identity, isolated local storage; never an exception to the synthetic profile. */
@Configuration(proxyBeanMethods = false)
@Profile("google-oidc-dev")
class GoogleOidcDevelopmentMode {
    @Bean LocalhostMode googleOidcDevelopmentStorage(Environment environment) {
        try {
            if (!Set.of("google-oidc-dev", "operations").equals(Set.of(environment.getActiveProfiles()))
                    || !"127.0.0.1".equals(environment.getProperty("server.address"))
                    || !"9443".equals(environment.getProperty("server.port"))
                    || !environment.getProperty("server.ssl.enabled", Boolean.class, false)
                    || !"127.0.0.1".equals(environment.getProperty("management.server.address"))
                    || !"19082".equals(environment.getProperty("management.server.port"))
                    || !"jdbc:postgresql://127.0.0.1:55448/nkw_oidc_dev".equals(environment.getProperty("spring.datasource.url"))
                    || !"nkw_oidc_runtime".equals(environment.getProperty("spring.datasource.username"))
                    || !"127.0.0.1".equals(environment.getProperty("spring.data.redis.host"))
                    || !"56395".equals(environment.getProperty("spring.data.redis.port"))
                    || !"https://localhost:9443".equals(environment.getProperty("identity.delivery.public-origin"))
                    || !environment.getProperty("identity.oidc.google.enabled", Boolean.class, false)
                    || !"https://accounts.google.com".equals(environment.getProperty("identity.oidc.google.issuer"))
                    || !"https://localhost:9443/api/auth/oidc/google/callback".equals(environment.getProperty("identity.oidc.google.login-redirect-uri"))
                    || !"https://localhost:9443/api/auth/oidc/google/link-callback".equals(environment.getProperty("identity.oidc.google.link-redirect-uri"))) throw new IllegalStateException();
            for (String property : java.util.List.of("identity.delivery.smtp-enabled", "knowledge.derivation.enabled",
                    "knowledge.derivation.scheduling-enabled", "nkw.localhost.synthetic-ai", "spring.flyway.enabled",
                    "notes.private-storage.enabled", "profile.avatar-storage.enabled", "profile.public-avatar-storage.enabled",
                    "publishing.media-storage.enabled", "server.tomcat.accesslog.enabled")) {
                if (environment.getProperty(property, Boolean.class, false)) throw new IllegalStateException();
            }
            Path root = Path.of(environment.getRequiredProperty("nkw.localhost.directory")).toAbsolutePath().normalize();
            if (!root.equals(root.toRealPath()) || !root.getFileName().toString().equals("google-oidc-dev")
                    || !root.getParent().getFileName().toString().equals(".runtime")
                    || !Files.isRegularFile(root.resolve("OIDC_DEV_INSTANCE"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(root.resolve("OIDC_DEV_INSTANCE")).equals("nkw-google-oidc-dev-1\n")
                    || Files.exists(root.resolve("LOCALHOST_INSTANCE"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
            ownerOnly(root);
            for (String name : java.util.List.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media")) {
                Path child = root.resolve(name);
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || !child.equals(child.toRealPath())) throw new IllegalStateException();
                ownerOnly(child);
            }
            return new LocalhostMode(root);
        } catch (Exception invalid) {
            throw new IllegalStateException("Isolated owner-only Google OIDC development configuration required");
        }
    }

    private static void ownerOnly(Path path) throws java.io.IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var owner = Files.getOwner(path);
            for (var entry : acl.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                        && (entry.permissions().contains(AclEntryPermission.READ_DATA)
                            || entry.permissions().contains(AclEntryPermission.WRITE_DATA))) throw new IllegalStateException();
            }
        } else {
            var permissions = Files.getPosixFilePermissions(path);
            if (permissions.stream().anyMatch(p -> p != PosixFilePermission.OWNER_READ
                    && p != PosixFilePermission.OWNER_WRITE && p != PosixFilePermission.OWNER_EXECUTE)) throw new IllegalStateException();
        }
    }
}
