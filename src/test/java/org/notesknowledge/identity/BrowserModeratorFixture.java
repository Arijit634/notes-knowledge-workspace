package org.notesknowledge.identity;

import java.sql.DriverManager;
import java.util.UUID;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only offline provisioning using the real protected operator command.
 * Does not authenticate a browser or add an application capability endpoint.
 */
public final class BrowserModeratorFixture {
    private BrowserModeratorFixture() { }
    public static void grant(JdbcTemplate admin, String jdbcUrl, String email) throws Exception {
        if (email == null || !email.matches("browser_[a-z0-9_]+@example\\.test")) throw new IllegalArgumentException();
        UUID target = admin.queryForObject("select user_id from identity.account where canonical_email=? and account_state='active' and email_verified_at is not null", UUID.class, email);
        UUID operator = PublicDiscoveryFixtures.account(admin);
        String role = "nkw_modop_" + operator.toString().replace("-", "");
        admin.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='nkw_moderator_operator') then create role nkw_moderator_operator nologin nosuperuser nocreatedb nocreaterole; end if; end $$");
        admin.execute("create role " + role + " login password 'synthetic-browser-operator-password' nosuperuser nocreatedb nocreaterole inherit");
        admin.execute("grant nkw_moderator_operator to " + role);
        admin.execute("grant usage on schema identity to nkw_moderator_operator");
        admin.execute("grant select(user_id,account_state,email_verified_at),update(updated_at) on identity.account to nkw_moderator_operator");
        admin.execute("grant select,insert,update(revoked_at) on identity.privilege_assignment to nkw_moderator_operator");
        admin.execute("grant insert on identity.security_audit_fact to nkw_moderator_operator");
        for (var capability : java.util.List.of(ModerationCapability.REVIEW, ModerationCapability.ENFORCE)) {
            try (var connection = DriverManager.getConnection(jdbcUrl, role, "synthetic-browser-operator-password")) {
                ModeratorProvisioningCommand.execute(connection, "grant", operator, target, capability, ModerationScope.allPublicReports());
            }
        }
    }
}
