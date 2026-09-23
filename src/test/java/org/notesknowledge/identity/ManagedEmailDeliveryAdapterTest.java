package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Tag("FAST")
class ManagedEmailDeliveryAdapterTest {
    @Test
    void mailFailureClassesAreSanitizedAndDoNotLeakProviderExceptions() {
        var sender = spy(configuredSender());
        var adapter = new ManagedEmailDeliveryAdapter(sender, "noreply@example.test", policy());
        var message = new SecurityEmailMessageRenderer.Message("Verification", "Synthetic body");
        doThrow(new MailParseException("private-provider-payload"))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(adapter.submit("user@example.test", message))
                .isEqualTo(SecurityEmailProviderPort.Outcome.NON_RETRYABLE);
        doThrow(new MailAuthenticationException("private-provider-payload"))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(adapter.submit("user@example.test", message))
                .isEqualTo(SecurityEmailProviderPort.Outcome.NON_RETRYABLE);
        doThrow(new MailSendException("private-provider-payload"))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(adapter.submit("user@example.test", message))
                .isEqualTo(SecurityEmailProviderPort.Outcome.AMBIGUOUS);
    }

    @Test
    void smtpSocketBoundsMustFitTheTypedProviderTimeout() {
        var sender = configuredSender();
        sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "999999");
        assertThatThrownBy(() -> new ManagedEmailDeliveryAdapter(sender,
                "noreply@example.test", policy()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JavaMailSenderImpl configuredSender() {
        var sender = new JavaMailSenderImpl();
        sender.setHost("smtp.example.test");
        sender.setUsername("synthetic-user");
        sender.setPassword("synthetic-password");
        sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        sender.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "true");
        sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
        sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "10000");
        sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "10000");
        return sender;
    }

    private SecurityEmailDeliveryProperties policy() {
        return new SecurityEmailDeliveryProperties(10, 2, 4, Duration.ofMinutes(2),
                Duration.ofSeconds(30), 5, Duration.ofSeconds(30),
                Duration.ofHours(1), Duration.ofSeconds(5), "synthetic_worker");
    }
}
