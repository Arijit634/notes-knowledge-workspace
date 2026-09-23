package org.notesknowledge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded 3A abuse-control policy; configured values require operational review. */
@ConfigurationProperties(prefix = "identity.rate")
record IdentityRateProperties(int windowSeconds, int providerWindowSeconds,
        int registrationCeiling,
        int verificationRequestCeiling, int verificationConfirmationCeiling,
        int passwordLoginCeiling, int aggregateCeiling, int providerCeiling) {
    private static final int MAX_RATE_CEILING = 1_000_000;

    IdentityRateProperties {
        if (windowSeconds < 1 || windowSeconds > 86_400
                || providerWindowSeconds < 1 || providerWindowSeconds > 86_400
                || registrationCeiling < 1 || verificationRequestCeiling < 1
                || verificationConfirmationCeiling < 1 || passwordLoginCeiling < 1
                || aggregateCeiling < 1 || providerCeiling < 1
                || registrationCeiling > MAX_RATE_CEILING
                || verificationRequestCeiling > MAX_RATE_CEILING
                || verificationConfirmationCeiling > MAX_RATE_CEILING
                || passwordLoginCeiling > MAX_RATE_CEILING
                || aggregateCeiling > MAX_RATE_CEILING
                || providerCeiling > MAX_RATE_CEILING) {
            throw new IllegalArgumentException("Invalid Identity rate-control policy");
        }
    }

    int ceiling(String control) {
        return switch (control) {
            case "REGISTRATION" -> registrationCeiling;
            case "VERIFICATION_REQUEST" -> verificationRequestCeiling;
            case "VERIFICATION_CONFIRMATION" -> verificationConfirmationCeiling;
            case "PASSWORD_LOGIN" -> passwordLoginCeiling;
            case "IDENTITY_GLOBAL" -> aggregateCeiling;
            case "SECURITY_EMAIL_PROVIDER" -> providerCeiling;
            default -> throw new IllegalArgumentException("Unknown Identity rate class");
        };
    }

    int windowSeconds(String control) {
        return "SECURITY_EMAIL_PROVIDER".equals(control)
                ? providerWindowSeconds : windowSeconds;
    }
}
