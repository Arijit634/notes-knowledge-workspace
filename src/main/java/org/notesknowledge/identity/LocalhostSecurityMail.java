package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.notesknowledge.LocalhostMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner-only outbox. Contains real local capabilities: never serve, log or archive it. */
@Component
@Profile("localhost")
final class LocalhostSecurityMail implements SecurityEmailProviderPort {
    private final LocalhostMode mode;
    LocalhostSecurityMail(LocalhostMode mode) { this.mode = mode; }

    public Outcome submit(String recipient, SecurityEmailMessageRenderer.Message message) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return Outcome.NON_RETRYABLE;
        try {
            var directory = mode.directory("outbox");
            if (!directory.equals(directory.toRealPath()) || !recipient.endsWith("@example.test")) return Outcome.NON_RETRYABLE;
            // The real durable worker supplies recipient and rendered message only
            // after its current eligibility checks. No account state is changed here.
            byte[] locator = new byte[16];
            new java.security.SecureRandom().nextBytes(locator);
            Files.writeString(directory.resolve(java.util.HexFormat.of().formatHex(locator) + ".txt"),
                    "LOCAL CONTROLLED DELIVERY — NOT INTERNET EMAIL\nTo: " + recipient + "\nSubject: " + message.subject() + "\n\n" + message.body(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return Outcome.SUBMITTED;
        } catch (Exception unavailable) { return Outcome.AMBIGUOUS; }
    }
}
