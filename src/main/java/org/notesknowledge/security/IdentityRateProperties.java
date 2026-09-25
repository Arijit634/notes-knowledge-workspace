package org.notesknowledge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded 3A abuse-control policy; configured values require operational review. */
@ConfigurationProperties(prefix = "identity.rate")
record IdentityRateProperties(int windowSeconds, int providerWindowSeconds,
        int registrationCeiling,
        int verificationRequestCeiling, int verificationConfirmationCeiling,
        int passwordLoginCeiling, int aggregateCeiling, int providerCeiling,
        int mfaCeiling, int mfaManagementCeiling, int recentAuthCeiling) {
    private static final int MAX_RATE_CEILING = 1_000_000;

    IdentityRateProperties {
        if (windowSeconds < 1 || windowSeconds > 86_400
                || providerWindowSeconds < 1 || providerWindowSeconds > 86_400
                || registrationCeiling < 1 || verificationRequestCeiling < 1
                || verificationConfirmationCeiling < 1 || passwordLoginCeiling < 1
                || aggregateCeiling < 1 || providerCeiling < 1
                || mfaCeiling < 1 || mfaManagementCeiling < 1 || recentAuthCeiling < 1
                || registrationCeiling > MAX_RATE_CEILING
                || verificationRequestCeiling > MAX_RATE_CEILING
                || verificationConfirmationCeiling > MAX_RATE_CEILING
                || passwordLoginCeiling > MAX_RATE_CEILING
                || aggregateCeiling > MAX_RATE_CEILING
                || providerCeiling > MAX_RATE_CEILING || mfaCeiling > MAX_RATE_CEILING
                || mfaManagementCeiling > MAX_RATE_CEILING
                || recentAuthCeiling > MAX_RATE_CEILING) {
            throw new IllegalArgumentException("Invalid Identity rate-control policy");
        }
    }

    int ceiling(String control) {
        return switch (control) {
            case "REGISTRATION" -> registrationCeiling;
            case "VERIFICATION_REQUEST" -> verificationRequestCeiling;
            case "VERIFICATION_CONFIRMATION" -> verificationConfirmationCeiling;
            case "PASSWORD_LOGIN" -> passwordLoginCeiling;
            case "MFA_TOTP", "MFA_RECOVERY" -> mfaCeiling;
            case "MFA_ENROLL", "MFA_CONFIRM" -> mfaManagementCeiling;
            case "PASSWORD_REAUTH" -> recentAuthCeiling;
            case "OIDC_LOGIN_START", "OIDC_LOGIN_CALLBACK", "OIDC_REAUTH_START",
                    "OIDC_REAUTH_CALLBACK" -> recentAuthCeiling;
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
