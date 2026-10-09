package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModeratorProvisioningDatabaseTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("operator_synthetic").withUsername("synthetic_migrator").withPassword("synthetic-operator-migration-password");
    JdbcTemplate jdbc;UUID operator,target;String operatorRole;
    @BeforeAll void migrate(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());Flyway.configure().dataSource(ds).load().migrate();jdbc=new JdbcTemplate(ds);
        operator=PublicDiscoveryFixtures.account(jdbc);operatorRole="nkw_modop_"+operator.toString().replace("-","");
        jdbc.execute("create role nkw_moderator_operator nologin nosuperuser nocreatedb nocreaterole");
        jdbc.execute("create role "+operatorRole+" login password 'synthetic-operator-password' nosuperuser nocreatedb nocreaterole inherit");
        jdbc.execute("grant nkw_moderator_operator to "+operatorRole);
        jdbc.execute("grant usage on schema identity to nkw_moderator_operator");
        jdbc.execute("grant select(user_id,account_state,email_verified_at),update(updated_at) on identity.account to nkw_moderator_operator");
        jdbc.execute("grant select,insert,update(revoked_at) on identity.privilege_assignment to nkw_moderator_operator");
        jdbc.execute("grant insert on identity.security_audit_fact to nkw_moderator_operator");
    }
    @BeforeEach void recipient(){target=PublicDiscoveryFixtures.account(jdbc);}
    @Test void protectedGrantRevokeIsAttributableIdempotentAndCurrent()throws Exception {
        apply("grant",operator,target,ModerationCapability.REVIEW,ModerationScope.allPublicReports());apply("grant",operator,target,ModerationCapability.REVIEW,ModerationScope.allPublicReports());
        assertThat(count("identity.privilege_assignment")).isEqualTo(1);assertThat(count("identity.security_audit_fact")).isEqualTo(1);
        var api=new PrivilegeAuthorizationApi(new PrivilegeAssignmentRepository(org.springframework.jdbc.core.simple.JdbcClient.create(jdbc)));
        assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.allPublicReports())).isTrue();assertThat(api.hasActiveCapability(target,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isFalse();
        apply("revoke",operator,target,ModerationCapability.REVIEW,ModerationScope.allPublicReports());apply("revoke",operator,target,ModerationCapability.REVIEW,ModerationScope.allPublicReports());
        assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.allPublicReports())).isFalse();assertThat(count("identity.security_audit_fact")).isEqualTo(2);
        UUID assignment=jdbc.queryForObject("select privilege_assignment_id from identity.privilege_assignment where user_id=?",UUID.class,target);
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact where target_user_id=? and actor_user_id=? and correlation_id=?",Integer.class,target,operator,assignment)).isEqualTo(2);
    }
    @Test void selfAssignmentActorSpoofingAndSuperuserShortcutsAreRejected()throws Exception {
        assertThatThrownBy(()->apply("grant",operator,operator,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->apply("grant",target,operator,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isInstanceOf(SecurityException.class);
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            assertThatThrownBy(()->ModeratorProvisioningCommand.execute(c,"grant",operator,target,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isInstanceOf(SecurityException.class);
        }
        assertThat(count("identity.privilege_assignment")).isZero();
    }
    @Test void ordinaryDatabaseLoginCannotProvisionEvenWithForgedActor()throws Exception {
        jdbc.execute("create role ordinary_operator_test login password 'synthetic-ordinary-password' nosuperuser nocreatedb nocreaterole");
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"ordinary_operator_test","synthetic-ordinary-password")) {
            assertThatThrownBy(()->ModeratorProvisioningCommand.execute(c,"grant",operator,target,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isInstanceOf(SecurityException.class);
        }
        assertThat(count("identity.privilege_assignment")).isZero();
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),operatorRole,"synthetic-operator-password");var s=c.createStatement()) {
            assertThatThrownBy(()->s.executeQuery("select * from identity.account")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeQuery("select * from notes.note")).isInstanceOf(SQLException.class);
        }
    }
    @Test void inactiveRecipientIsRejectedAndScopeNeverExpands()throws Exception {
        UUID scope=jdbc.queryForObject("select uuidv7()",UUID.class);apply("grant",operator,target,ModerationCapability.REVIEW,ModerationScope.publicReport(scope));
        var api=new PrivilegeAuthorizationApi(new PrivilegeAssignmentRepository(org.springframework.jdbc.core.simple.JdbcClient.create(jdbc)));
        assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.publicReport(scope))).isTrue();assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.allPublicReports())).isFalse();
        assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.publicReport(UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401")))).isFalse();
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",target);
        assertThatThrownBy(()->apply("grant",operator,target,ModerationCapability.ENFORCE,ModerationScope.allPublicReports())).isInstanceOf(SecurityException.class);
        assertThat(api.hasActiveCapability(target,ModerationCapability.REVIEW,ModerationScope.publicReport(scope))).isFalse();
    }
    private void apply(String action,UUID actor,UUID recipient,ModerationCapability cap,ModerationScope scope)throws Exception {try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),operatorRole,"synthetic-operator-password")){ModeratorProvisioningCommand.execute(c,action,actor,recipient,cap,scope);}}
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where "+(table.endsWith("assignment")?"user_id":"target_user_id")+"=?",Integer.class,target);}
}
