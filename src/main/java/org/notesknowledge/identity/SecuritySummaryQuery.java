package org.notesknowledge.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Owner-scoped projection; no credential, factor, or provider-subject material leaves Identity. */
@Repository
@IdentityCoreEnabled
class SecuritySummaryQuery {
    record OidcLink(UUID linkId, String provider, Instant linkedAt) { }
    record Summary(String email, boolean passwordConfigured, String mfaState,
            List<OidcLink> oidcLinks) { }
    private record Core(String email, boolean passwordConfigured, String mfaState) { }

    private final JdbcClient jdbc;

    SecuritySummaryQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Summary forOwner(UUID userId) {
        Core core = jdbc.sql("""
                select a.display_email, a.password_verifier is not null as password_configured,
                       m.state as mfa_state
                from identity.account a
                left join identity.mfa_configuration m on m.user_id = a.user_id
                where a.user_id = :owner and a.account_state = 'active'
                  and a.email_verified_at is not null
                """).param("owner", userId).query((rs, row) -> new Core(
                rs.getString("display_email"), rs.getBoolean("password_configured"),
                safeMfaState(rs.getString("mfa_state"))))
                .optional().orElseThrow(() -> ApiFailureException.of(
                        ApiFailureException.Kind.INVALID_CREDENTIALS));
        List<OidcLink> links = jdbc.sql("""
                select external_identity_link_id, linked_at
                from identity.external_identity_link
                where user_id = :owner and issuer = 'https://accounts.google.com'
                  and revoked_at is null
                order by linked_at, external_identity_link_id
                """).param("owner", userId).query((rs, row) -> new OidcLink(
                rs.getObject("external_identity_link_id", UUID.class), "google",
                rs.getTimestamp("linked_at").toInstant())).list();
        return new Summary(core.email(), core.passwordConfigured(), core.mfaState(), links);
    }

    private static String safeMfaState(String state) {
        if (state == null) return "disabled";
        return switch (state) {
            case "enrollment_pending" -> "enrollmentPending";
            case "active" -> "active";
            default -> throw new IllegalStateException("Unexpected MFA state");
        };
    }
}
