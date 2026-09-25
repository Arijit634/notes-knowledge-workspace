package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Component;

@Component
@IdentityCoreEnabled
final class RecoveryCodeService {
    private final SecureRandom random = new SecureRandom();
    private final MfaProperties policy;
    RecoveryCodeService(MfaProperties policy) { this.policy = policy; }

    List<String> issue() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < policy.recoveryCodeCount(); i++) {
            byte[] material = new byte[32];
            random.nextBytes(material);
            codes.add(Base64.getUrlEncoder().withoutPadding().encodeToString(material));
        }
        return List.copyOf(codes);
    }

    byte[] digest(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("identity:mfa:recovery:v1:".getBytes(StandardCharsets.US_ASCII));
            return digest.digest(code.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Recovery digest primitive unavailable");
        }
    }
}
