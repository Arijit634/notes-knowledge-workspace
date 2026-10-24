package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.LocalhostMode;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("FAST") @Tag("SECURITY")
class LocalhostSecurityMailTest {
    @TempDir Path root;
    LocalhostSecurityMail mailbox() throws Exception {
        Files.writeString(root.resolve("LOCALHOST_INSTANCE"), "nkw-localhost-1\n");
        for (String name : java.util.List.of("outbox", "private-attachment", "private-avatar", "public-profile-avatar", "public-publication-media"))
            Files.createDirectory(root.resolve(name));
        var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("server.ssl.enabled", "true").withProperty("nkw.localhost.directory", root.toRealPath().toString())
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55438/nkw_owner_local")
                .withProperty("spring.datasource.username", "nkw_local_runtime");
        environment.setActiveProfiles("operations", "localhost"); return new LocalhostSecurityMail(new LocalhostMode(environment));
    }
    @Test void submitsRenderedMessageOnlyToProtectedLocalOutbox() throws Exception {
        var mailbox = mailbox();
        assertThat(mailbox.submit("owner@example.test", new SecurityEmailMessageRenderer.Message("Synthetic notice", "Synthetic body")))
                .isEqualTo(SecurityEmailProviderPort.Outcome.SUBMITTED);
        try (var files=Files.list(root.resolve("outbox"))) {
            var messages=files.toList(); assertThat(messages).hasSize(1);
            assertThat(Files.readString(messages.getFirst())).contains("LOCAL CONTROLLED DELIVERY", "Synthetic body");
        }
    }
    @Test void neverPretendsInternetDeliverySucceeded() throws Exception {
        assertThat(mailbox().submit("owner@invalid.example", new SecurityEmailMessageRenderer.Message("Synthetic", "Synthetic")))
                .isEqualTo(SecurityEmailProviderPort.Outcome.NON_RETRYABLE);
        try (var files=Files.list(root.resolve("outbox"))) { assertThat(files.count()).isZero(); }
    }
    @Test void mailCannotBeSubmittedInsideTransaction() throws Exception {
        var mailbox=mailbox(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(mailbox.submit("owner@example.test", new SecurityEmailMessageRenderer.Message("Synthetic", "Synthetic")))
                .isEqualTo(SecurityEmailProviderPort.Outcome.NON_RETRYABLE); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
