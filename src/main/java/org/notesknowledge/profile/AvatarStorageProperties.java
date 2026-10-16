package org.notesknowledge.profile;

import java.net.URI;

/** Configuration metadata only. Credentials are separately injected and never bound into this value. */
record AvatarStorageProperties(boolean enabled, String profile, String endpoint, String region,
        String bucket, String credentialsSource, boolean privateBucketConfirmed, boolean reconciliationEnabled) {
    void requireConfigured() {
        try {
            if (!enabled || !"oci-s3-private-v1".equals(profile) || !"injected".equals(credentialsSource)
                    || !privateBucketConfirmed || region == null || !region.matches("[a-z]{2}-[a-z0-9]+-[0-9]+")
                    || bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw unavailable();
            var uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !uri.getHost().matches("[a-z0-9]{1,63}\\.compat\\.objectstorage\\."+java.util.regex.Pattern.quote(region)+"\\.(?:oraclecloud\\.com|oci\\.customer-oci\\.com)")
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty()) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Avatar storage configuration unavailable"); }
    @Override public String toString() { return "AvatarStorageProperties[REDACTED]"; }
}
