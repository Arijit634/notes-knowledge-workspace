package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled external mailbox only. Notice delivery is accepted without retaining
 * bodies; verification capabilities stay in process and never enter IPC/evidence.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserMailboxFixture {
    @Bean Mailbox browserMailbox() { return new Mailbox(); }
    static final class Mailbox implements SecurityEmailProviderPort {
        private String verification;
        public Outcome submit(String recipient, SecurityEmailMessageRenderer.Message message) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("Mail submission inside transaction");
            String marker = "/verify-email#token=";
            int start = message.body().indexOf(marker);
            if (start >= 0) {
                if (verification != null) throw new AssertionError("Ambiguous pending verification");
                start += marker.length();
                verification = message.body().substring(start, message.body().indexOf('\n', start));
            }
            return Outcome.SUBMITTED;
        }
        public String toString() { return "BrowserMailbox[REDACTED]"; }
    }
    public static String verificationToken(ApplicationContext app) {
        var claims = app.getBean(SecurityEmailDeliveryRepository.class).claimReady(app.getBean(Clock.class).instant(),
            new LeaseOwner("browser_fixture_worker"), new LeasePolicy(Duration.ofMinutes(2), 10), 10);
        for (var claim : claims) app.getBean(SecurityEmailWorker.class).process(claim);
        var mailbox = app.getBean(Mailbox.class);
        String token = mailbox.verification; mailbox.verification = null;
        if (token == null) throw new IllegalStateException("Synthetic verification delivery unavailable");
        return token;
    }
}
