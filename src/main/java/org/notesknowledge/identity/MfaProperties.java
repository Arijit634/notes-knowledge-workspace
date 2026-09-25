package org.notesknowledge.identity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deliberately narrow and validated initial MFA timing/verification policy. */
@ConfigurationProperties(prefix = "identity.mfa")
record MfaProperties(Duration challengeLifetime, Duration enrollmentLifetime,
        Duration recentAuthLifetime,
        int timestepSeconds, int digits, int allowedSkewSteps, int recoveryCodeCount,
        int maxChallengeFailures) {
    MfaProperties {
        if (challengeLifetime == null || challengeLifetime.isNegative()
                || challengeLifetime.isZero() || challengeLifetime.compareTo(Duration.ofMinutes(10)) > 0
                || enrollmentLifetime == null || enrollmentLifetime.isNegative()
                || enrollmentLifetime.isZero() || enrollmentLifetime.compareTo(Duration.ofMinutes(15)) > 0
                || recentAuthLifetime == null || recentAuthLifetime.isNegative()
                || recentAuthLifetime.isZero() || recentAuthLifetime.compareTo(Duration.ofMinutes(15)) > 0
                || timestepSeconds != 30 || digits != 6 || allowedSkewSteps < 0
                || allowedSkewSteps > 1 || recoveryCodeCount < 5 || recoveryCodeCount > 12
                || maxChallengeFailures < 3 || maxChallengeFailures > 8) {
            throw new IllegalArgumentException("Invalid MFA security policy");
        }
    }
}
