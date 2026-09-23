package org.notesknowledge.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Enabled only with explicit SMTP configuration and separate live-provider authorization. */
@Component
@ConditionalOnProperty(prefix = "identity.delivery", name = "smtp-enabled", havingValue = "true")
final class ManagedEmailDeliveryAdapter implements SecurityEmailProviderPort {
    private final JavaMailSender sender;
    private final String from;

    ManagedEmailDeliveryAdapter(JavaMailSender sender,
            @org.springframework.beans.factory.annotation.Value("${identity.delivery.from:}") String from,
            SecurityEmailDeliveryProperties policy) {
        this.sender = sender;
        if (from.isBlank()) {
            throw new IllegalArgumentException("Security email sender is not configured");
        }
        if (!(sender instanceof JavaMailSenderImpl configured)
                || configured.getHost() == null || configured.getHost().isBlank()
                || configured.getUsername() == null || configured.getUsername().isBlank()
                || configured.getPassword() == null || configured.getPassword().isBlank()
                || !"true".equalsIgnoreCase(configured.getJavaMailProperties()
                        .getProperty("mail.smtp.auth"))
                || !"true".equalsIgnoreCase(configured.getJavaMailProperties()
                        .getProperty("mail.smtp.starttls.required"))) {
            throw new IllegalArgumentException("Authenticated TLS SMTP is required");
        }
        this.from = from;
        // SMTP's own connect/read/write bounds are the enforceable provider timeout;
        // interrupting a worker Future would not reliably cancel a socket write.
        long timeout = policy.providerTimeout().toMillis();
        long total = 0;
        for (String name : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
            String configuredValue = configured.getJavaMailProperties()
                    .getProperty("mail.smtp." + name);
            if (configuredValue == null) {
                throw new IllegalArgumentException("SMTP timeout exceeds delivery policy");
            }
            long component = Long.parseLong(configuredValue);
            if (component < 1 || component > timeout) {
                throw new IllegalArgumentException("SMTP timeout exceeds delivery policy");
            }
            total += component;
        }
        if (total > timeout) {
            throw new IllegalArgumentException("Combined SMTP timeouts exceed delivery policy");
        }
    }

    @Override
    public Outcome submit(String recipient, SecurityEmailMessageRenderer.Message message) {
        var mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipient);
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            sender.send(mail);
            return Outcome.SUBMITTED;
        } catch (MailAuthenticationException | MailParseException
                | MailPreparationException exception) {
            return Outcome.NON_RETRYABLE;
        } catch (MailSendException exception) {
            return Outcome.AMBIGUOUS;
        } catch (MailException exception) {
            return Outcome.RETRYABLE;
        }
    }
}
