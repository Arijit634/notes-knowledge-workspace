package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Tag("SECURITY")
@Testcontainers
class PrivilegeAssignmentDatabaseTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("privilege_assignment")
            .withUsername("privilege_migrator")
            .withPassword("synthetic-privilege-migrator-password");

    static JdbcTemplate jdbc;
    static PrivilegeAuthorizationApi authorization;

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        authorization = new PrivilegeAuthorizationApi(new PrivilegeAssignmentRepository(
                JdbcClient.create(jdbc.getDataSource())));
    }

    @Test
    void migrationRestrictsCapabilityScopeProvenanceAndActiveDuplicates() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes where schemaname = 'identity'
                  and tablename = 'privilege_assignment'
                """, String.class)).contains("ux_privilege_assignment_active_scope",
                        "ix_privilege_assignment_user_history");
        assertThat(jdbc.queryForList("""
                select c.confdeltype::text from pg_constraint c
                where c.conrelid = 'identity.privilege_assignment'::regclass
                  and c.contype = 'f'
                """, String.class)).containsExactlyInAnyOrder("r", "r");

        UUID assigned = account();
        UUID assigner = account();
        assertInvalid(assigned, assigner, "private_note.read", "public_report", null);
        assertInvalid(assigned, assigner, "moderation.review", "private_note", null);
        assertInvalid(assigned, assigned, "moderation.review", "public_report", null);
        assertInvalid(assigned, uuid(), "moderation.review", "public_report", null);
        assertInvalid(uuid(), assigner, "moderation.review", "public_report", null);

        UUID assignment = insert(assigned, assigner, "moderation.review", null);
        assertThatThrownBy(() -> insert(assigned, assigner, "moderation.review", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                update identity.privilege_assignment set revoked_at = now()
                where privilege_assignment_id = ?
                """, assignment);
        insert(assigned, assigner, "moderation.review", null);
    }

    @Test
    void currentStateLookupDeniesMismatchesAndRevocationWithoutSessionRefresh() {
        UUID assigned = account();
        UUID assigner = account();
        UUID report = uuid();
        UUID otherReport = uuid();
        ModerationScope requested = ModerationScope.publicReport(report);

        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.REVIEW,
                requested)).isFalse();
        UUID assignment = insert(assigned, assigner, "moderation.review", report);
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.REVIEW,
                requested)).isTrue();
        authorization.requireActiveCapability(assigned, ModerationCapability.REVIEW, requested);
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.ENFORCE,
                requested)).isFalse();
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.REVIEW,
                ModerationScope.publicReport(otherReport))).isFalse();
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.REVIEW,
                ModerationScope.allPublicReports())).isFalse();
        assertThat(authorization.hasActiveCapability(assigner, ModerationCapability.REVIEW,
                requested)).isFalse();

        jdbc.update("""
                update identity.privilege_assignment set revoked_at = now()
                where privilege_assignment_id = ?
                """, assignment);
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.REVIEW,
                requested)).isFalse();
        assertThatThrownBy(() -> authorization.requireActiveCapability(assigned,
                ModerationCapability.REVIEW, requested))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void broadPublicReportAssignmentStillRequiresCurrentAccountEligibility() {
        UUID assigned = account();
        UUID assigner = account();
        insert(assigned, assigner, "moderation.enforce", null);
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.ENFORCE,
                ModerationScope.publicReport(uuid()))).isTrue();
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?",
                assigned);
        assertThat(authorization.hasActiveCapability(assigned, ModerationCapability.ENFORCE,
                ModerationScope.allPublicReports())).isFalse();
    }

    private static void assertInvalid(UUID assigned, UUID assigner, String capability,
            String scopeKind, UUID scopeId) {
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.privilege_assignment
                    (privilege_assignment_id, user_id, capability_code, scope_kind,
                     scope_id, assigned_by_user_id, granted_at)
                values (uuidv7(), ?, ?, ?, ?, ?, now())
                """, assigned, capability, scopeKind, scopeId, assigner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID insert(UUID assigned, UUID assigner, String capability, UUID scopeId) {
        UUID id = uuid();
        jdbc.update("""
                insert into identity.privilege_assignment
                    (privilege_assignment_id, user_id, capability_code, scope_kind,
                     scope_id, assigned_by_user_id, granted_at)
                values (?, ?, ?, 'public_report', ?, ?, now())
                """, id, assigned, capability, scopeId, assigner);
        return id;
    }

    private static UUID account() {
        UUID id = uuid();
        String email = "privilege-" + id + "@example.test";
        jdbc.update("""
                insert into identity.account
                    (user_id, canonical_email, display_email, email_verified_at,
                     account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, id, email, email);
        return id;
    }

    private static UUID uuid() {
        return jdbc.queryForObject("select uuidv7()", UUID.class);
    }
}
