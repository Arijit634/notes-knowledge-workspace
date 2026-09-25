package org.notesknowledge.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class SecurityEmailMessageRenderer {
    record Message(String subject, String body) {
        @Override public String toString() { return "SecurityEmailMessage[REDACTED]"; }
    }

    private final String origin;

    SecurityEmailMessageRenderer(@Value("${identity.delivery.public-origin:}") String origin) {
        this.origin = origin;
    }

    Message verification(String token) {
        String link = continuation("/verify-email#token=", token);
        return new Message("Verify your Notes & Knowledge Workspace email",
                "To verify your email address, open this link: " + link
                + "\nThis link expires in 24 hours. If you did not request it, ignore this message.");
    }

    Message passwordReset(String token) {
        String link = continuation("/reset-password#token=", token);
        return new Message("Reset your Notes & Knowledge Workspace password",
                "To reset your password, open this link: " + link
                + "\nThis link expires in 1 hour. If you did not request it, ignore this message.");
    }

    Message passwordResetCompleted() {
        return new Message("Your Notes & Knowledge Workspace password was reset",
                "Your account password was reset. If this was not you, contact support.");
    }

    private String continuation(String path, String token) {
        if (origin.isBlank()) {
            throw new IllegalStateException("Public origin unavailable");
        }
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"".equals(uri.getPath())) {
            throw new IllegalStateException("Invalid public origin");
        }
        return origin + path + URLEncoder.encode(token, StandardCharsets.US_ASCII);
    }
}
