package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

@Tag("FAST") @Tag("SECURITY")
class GoogleOidcDevelopmentModeTest {
    @TempDir Path temporary;

    MockEnvironment configuration() throws Exception {
        Path root = temporary.resolve(".runtime/google-oidc-dev"); Files.createDirectories(root);
        var acl = Files.getFileAttributeView(root, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setPrincipal(Files.getOwner(root))
            .setType(AclEntryType.ALLOW).setPermissions(AclEntryPermission.values())
            .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        else Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(root.resolve("OIDC_DEV_INSTANCE"), "nkw-google-oidc-dev-1\n");
        for (String name : List.of("outbox","private-attachment","private-avatar","public-profile-avatar","public-publication-media")) {
            Path child=Files.createDirectories(root.resolve(name));
            if(acl==null) Files.setPosixFilePermissions(child,PosixFilePermissions.fromString("rwx------"));
        }
        var e = new MockEnvironment().withProperty("server.address","127.0.0.1").withProperty("server.port","9443")
            .withProperty("server.ssl.enabled","true").withProperty("management.server.address","127.0.0.1").withProperty("management.server.port","19082")
            .withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55448/nkw_oidc_dev")
            .withProperty("spring.datasource.username","nkw_oidc_runtime").withProperty("spring.data.redis.host","127.0.0.1")
            .withProperty("spring.data.redis.port","56395").withProperty("identity.delivery.public-origin","https://localhost:9443")
            .withProperty("identity.oidc.google.enabled","true").withProperty("identity.oidc.google.issuer","https://accounts.google.com")
            .withProperty("identity.oidc.google.login-redirect-uri","https://localhost:9443/api/auth/oidc/google/callback")
            .withProperty("identity.oidc.google.link-redirect-uri","https://localhost:9443/api/auth/oidc/google/link-callback")
            .withProperty("nkw.localhost.directory",root.toRealPath().toString());
        e.setActiveProfiles("operations","google-oidc-dev");return e;
    }
    LocalhostMode mode(MockEnvironment e) { return new GoogleOidcDevelopmentMode().googleOidcDevelopmentStorage(e); }

    @Test void isolatedModeUsesItsOwnDirectoryAndOrdinaryEmailOutbox() throws Exception {
        var e=configuration();var mode=mode(e);
        assertThat(mode.syntheticEmailOnly()).isFalse();
        assertThat(mode.directory("outbox")).isEqualTo(Path.of(e.getProperty("nkw.localhost.directory")).resolve("outbox"));
    }
    @Test void syntheticModeIsStillUnableToEnableGoogle() throws Exception {
        assertThatThrownBy(()->new LocalhostMode(configuration())).hasMessage("Protected loopback localhost configuration required").hasNoCause();
    }
    @Test void rejectsOriginalDatabaseRemoteNetworkWrongCallbacksAndDdlRole() throws Exception {
        for (var entry : Map.ofEntries(
            Map.entry("server.address","0.0.0.0"),Map.entry("server.ssl.enabled","false"),Map.entry("server.port","8080"),
            Map.entry("management.server.address","0.0.0.0"),Map.entry("management.server.port","19081"),
            Map.entry("spring.datasource.url","jdbc:postgresql://127.0.0.1:55438/nkw_owner_local"),
            Map.entry("spring.datasource.username","nkw_oidc_migrator"),Map.entry("spring.data.redis.port","56385"),
            Map.entry("identity.oidc.google.issuer","https://untrusted.example.test"),
            Map.entry("identity.oidc.google.login-redirect-uri","https://localhost:9443/api/auth/oidc/google/callback?unsafe=1"),
            Map.entry("identity.oidc.google.link-redirect-uri","http://localhost:9443/api/auth/oidc/google/link-callback"),
            Map.entry("identity.delivery.public-origin","https://example.test")).entrySet()) {
            var e=configuration().withProperty(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->mode(e)).hasMessage("Isolated owner-only Google OIDC development configuration required").hasNoCause();
        }
    }
    @Test void refusesExternalMailAiCloudAndUncontrolledProfiles() throws Exception {
        for(String key:List.of("identity.delivery.smtp-enabled","knowledge.derivation.enabled","knowledge.derivation.scheduling-enabled",
                "nkw.localhost.synthetic-ai","spring.flyway.enabled","notes.private-storage.enabled","profile.avatar-storage.enabled",
                "profile.public-avatar-storage.enabled","publishing.media-storage.enabled","server.tomcat.accesslog.enabled")) {
            var e=configuration().withProperty(key,"true");assertThatThrownBy(()->mode(e)).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        for(String profile:List.of("localhost","test","production")) {
            var e=configuration();e.setActiveProfiles("operations","google-oidc-dev",profile);
            assertThatThrownBy(()->mode(e)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void refusesMissingMarkerAndOriginalInstanceMarker() throws Exception {
        var e=configuration();Path root=Path.of(e.getProperty("nkw.localhost.directory"));
        Files.delete(root.resolve("OIDC_DEV_INSTANCE"));assertThatThrownBy(()->mode(e)).isInstanceOf(IllegalStateException.class);
        Files.writeString(root.resolve("OIDC_DEV_INSTANCE"),"nkw-google-oidc-dev-1\n");Files.writeString(root.resolve("LOCALHOST_INSTANCE"),"nkw-localhost-1\n");
        assertThatThrownBy(()->mode(e)).isInstanceOf(IllegalStateException.class);
    }
    @Test void normalProfilesNeverInstallRealDevelopmentTransports() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(GoogleOidcDevelopmentMode.class)
            .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(LocalhostMode.class));
    }
}
