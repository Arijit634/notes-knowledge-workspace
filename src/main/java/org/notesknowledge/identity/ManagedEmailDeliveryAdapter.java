package org.notesknowledge.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
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
            @org.springframework.beans.factory.annotation.Value("${identity.delivery.from:}") String from) {
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
    }

    @Override
    public void submit(String recipient, SecurityEmailMessageRenderer.Message message) {
        var mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipient);
        mail.setSubject(message.subject());
        mail.setText(message.body());
        sender.send(mail);
    }
}
