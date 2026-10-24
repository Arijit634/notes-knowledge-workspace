package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("FAST") @Tag("SECURITY")
class LocalhostTransportTest {
    @TempDir Path root;
    static final String KEY = "private-attachment/" + "a".repeat(64);

    MockEnvironment configuration() throws Exception {
        Files.writeString(root.resolve("LOCALHOST_INSTANCE"), "nkw-localhost-1\n");
        for (String name : java.util.List.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media"))
            Files.createDirectories(root.resolve(name));
        var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("server.ssl.enabled", "true").withProperty("nkw.localhost.directory", root.toRealPath().toString())
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55438/nkw_owner_local")
                .withProperty("spring.datasource.username", "nkw_local_runtime");
        environment.setActiveProfiles("localhost", "operations"); return environment;
    }
    LocalhostObjectDirectory objects() throws Exception {
        return new LocalhostObjectDirectory(new LocalhostMode(configuration()), "private-attachment");
    }
    @Test void normalProfilesDoNotInstallControlledTransports() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(LocalhostMode.class, LocalhostShutdown.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalhostMode.class).doesNotHaveBean(LocalhostShutdown.class));
    }
    @Test void localhostRequiresLoopbackTlsOperationsAndOwnedState() throws Exception {
        var environment = configuration(); assertThat(new LocalhostMode(environment)).isNotNull();
        for (var entry : java.util.Map.of("server.address", "0.0.0.0", "server.ssl.enabled", "false").entrySet()) {
            var invalid = configuration().withProperty(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> new LocalhostMode(invalid)).hasMessage("Protected loopback localhost configuration required");
        }
        environment.setActiveProfiles("localhost");
        assertThatThrownBy(() -> new LocalhostMode(environment)).isInstanceOf(IllegalStateException.class);
        var missing = configuration(); Files.delete(root.resolve("LOCALHOST_INSTANCE"));
        assertThatThrownBy(() -> new LocalhostMode(missing)).isInstanceOf(IllegalStateException.class);
    }
    @Test void localModeCannotActivateExternalTransportsOrAi() throws Exception {
        for (String property : java.util.List.of("identity.delivery.smtp-enabled", "identity.oidc.google.enabled",
                "notes.private-storage.enabled", "profile.avatar-storage.enabled", "profile.public-avatar-storage.enabled",
                "publishing.media-storage.enabled", "knowledge.derivation.enabled")) {
            var invalid = configuration().withProperty(property, "true");
            assertThatThrownBy(() -> new LocalhostMode(invalid)).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }
    @Test void controlledModeCannotAccidentallyUseAnotherDatabaseOrDdlRole() throws Exception {
        for (String url : java.util.List.of("jdbc:postgresql://example.invalid:5432/nkw_owner_local",
                "jdbc:postgresql://127.0.0.1:55438/unrelated", "jdbc:postgresql://127.0.0.1:55438/nkw_owner_local?user=another")) {
            var invalid = configuration().withProperty("spring.datasource.url", url);
            assertThatThrownBy(() -> new LocalhostMode(invalid)).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        var invalid = configuration().withProperty("spring.datasource.username", "nkw_local_migrator");
        assertThatThrownBy(() -> new LocalhostMode(invalid)).isInstanceOf(IllegalStateException.class);
    }
    @Test void objectWritesAreCreateOnlyAndSurviveReconstruction() throws Exception {
        var objects = objects(); objects.write(KEY, new ByteArrayInputStream(new byte[]{1,2,3}), 3);
        assertThatThrownBy(() -> objects.write(KEY, new ByteArrayInputStream(new byte[]{9}), 1)).isInstanceOf(RuntimeException.class);
        try (var input = objects().open(KEY, 0, 3)) { assertThat(input.readAllBytes()).containsExactly(1,2,3); }
    }
    @Test void shortAndLongWritesLeaveNoAcceptedObject() throws Exception {
        var objects = objects();
        for (int size : new int[]{1,3}) {
            assertThatThrownBy(() -> objects.write(KEY, new ByteArrayInputStream(new byte[]{1,2}), size)).isInstanceOf(RuntimeException.class);
            assertThat(objects.open(KEY, 0, 1)).isNull();
        }
    }
    @Test void rangesAreBoundedAndMissingObjectsAreNotInvented() throws Exception {
        var objects = objects(); assertThat(objects.open(KEY, 0, 1)).isNull();
        objects.write(KEY, new ByteArrayInputStream(new byte[]{1,2,3,4}), 4);
        try (var input = objects.open(KEY, 1, 2)) { assertThat(input.readAllBytes()).containsExactly(2,3); }
        assertThatThrownBy(() -> objects.open(KEY, 3, 2)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> objects.open(KEY, -1, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> objects.open(KEY, Long.MAX_VALUE, 1)).isInstanceOf(RuntimeException.class);
    }
    @Test void namespaceAndTraversalCannotReachOtherObjects() throws Exception {
        var objects = objects();
        for (String reference : java.util.List.of("../outside", "public-publication-media/"+"a".repeat(64), "private-attachment/../outside", "private-attachment/"+"A".repeat(64)))
            assertThatThrownBy(() -> objects.open(reference, 0, 1)).isInstanceOf(RuntimeException.class);
    }
    @Test void deletionIsIdempotentAndInventoryIsExclusiveBoundedAndOrdered() throws Exception {
        var objects = objects();
        objects.write(KEY, new ByteArrayInputStream(new byte[]{1}), 1);
        String next = "private-attachment/"+"b".repeat(64); objects.write(next, new ByteArrayInputStream(new byte[]{2}), 1);
        assertThat(objects.inventory(Instant.now().plusSeconds(5), null, 1)).extracting(LocalhostObjectDirectory.ObjectEntry::reference).containsExactly(KEY);
        assertThat(objects.inventory(Instant.now().plusSeconds(5), KEY, 1)).extracting(LocalhostObjectDirectory.ObjectEntry::reference).containsExactly(next);
        assertThat(objects.inventory(Instant.EPOCH, null, 1)).isEmpty();
        objects.delete(KEY); objects.delete(KEY); assertThat(objects.open(KEY,0,1)).isNull();
    }
    @Test void transportRefusesIoInsideDatabaseTransaction() throws Exception {
        var objects = objects(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> objects.write(KEY,new ByteArrayInputStream(new byte[]{1}),1)).isInstanceOf(RuntimeException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
