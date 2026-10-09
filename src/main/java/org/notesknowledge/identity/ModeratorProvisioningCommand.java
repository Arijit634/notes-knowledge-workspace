package org.notesknowledge.identity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Offline operator entry point, not a Spring bean or HTTP/application privilege API.
 * Database authentication and explicit restricted role membership are the trust boundary.
 */
public final class ModeratorProvisioningCommand {
    private ModeratorProvisioningCommand(){ }
    public static void main(String[] arguments) {
        try {
            if(arguments.length!=5)throw new IllegalArgumentException("Invalid command");
            var actor=UUID.fromString(arguments[1]);var target=UUID.fromString(arguments[2]);
            var capability=java.util.Arrays.stream(ModerationCapability.values()).filter(c->c.code().equals(arguments[3])).findFirst().orElseThrow();
            var scope=arguments[4].equals("all-public-reports")?ModerationScope.allPublicReports():ModerationScope.publicReport(UUID.fromString(arguments[4]));
            try(var connection=DriverManager.getConnection(required("NKW_MODERATOR_OPERATOR_JDBC_URL"),required("NKW_MODERATOR_OPERATOR_USER"),required("NKW_MODERATOR_OPERATOR_PASSWORD"))) {
                execute(connection,arguments[0],actor,target,capability,scope);
            }
            System.out.println("moderator_provisioning_committed");
        }catch(Exception denied){System.err.println("moderator_provisioning_failed");System.exit(1);}
    }
    private static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing operator configuration");return value;}
    static void execute(Connection connection,String action,UUID actor,UUID target,ModerationCapability capability,ModerationScope scope)throws SQLException {
        if(!java.util.Set.of("grant","revoke").contains(action)||actor.equals(target)||capability==null||scope==null)throw new IllegalArgumentException("Invalid provisioning request");
        if(!connection.getAutoCommit())throw new IllegalArgumentException("Dedicated operator connection required");
        connection.setAutoCommit(false);
        try {
            // No ordinary app login or caller-supplied actor ID can satisfy server-authenticated
            // role membership. Principal-to-actor binding makes audit attribution non-spoofable.
            try(var check=connection.prepareStatement("""
                select current_user=session_user and current_user=? and not r.rolsuper and exists(
                    select 1 from pg_auth_members m join pg_roles g on g.oid=m.roleid
                    where m.member=r.oid and g.rolname='nkw_moderator_operator')
                from pg_roles r where r.rolname=current_user
                """)) {
                check.setString(1,"nkw_modop_"+actor.toString().replace("-",""));
                try(var result=check.executeQuery()){if(!result.next()||!result.getBoolean(1))throw new SecurityException("Protected operator required");}
            }
            int eligible=0;
            try(var lock=connection.prepareStatement("select user_id,account_state,email_verified_at from identity.account where user_id in (?,?) order by user_id for update")) {
                lock.setObject(1,actor);lock.setObject(2,target);
                try(var rows=lock.executeQuery()){while(rows.next())if("active".equals(rows.getString(2))&&rows.getTimestamp(3)!=null)eligible++;}
            }
            if(eligible!=2)throw new SecurityException("Eligible participants required");
            String sql=action.equals("grant")?"""
                insert into identity.privilege_assignment(privilege_assignment_id,user_id,capability_code,scope_kind,scope_id,assigned_by_user_id,granted_at)
                values(uuidv7(),?,?,'public_report',?,?,clock_timestamp()) on conflict do nothing returning privilege_assignment_id
                """:"""
                update identity.privilege_assignment set revoked_at=greatest(granted_at,clock_timestamp())
                where user_id=? and capability_code=? and scope_kind='public_report' and scope_id is not distinct from ? and revoked_at is null returning privilege_assignment_id
                """;
            UUID changed;
            try(var write=connection.prepareStatement(sql)) {
                write.setObject(1,target);write.setString(2,capability.code());write.setObject(3,scope.reportId());
                if(action.equals("grant"))write.setObject(4,actor);
                try(var result=write.executeQuery()){changed=result.next()?result.getObject(1,UUID.class):null;}
            }
            if(changed!=null)try(var audit=connection.prepareStatement("""
                insert into identity.security_audit_fact(audit_fact_id,actor_user_id,target_user_id,event_category,outcome_code,reason_code,correlation_id,occurred_at)
                values(uuidv7(),?,?,'privilege_assignment',?,'protected_operator',?,clock_timestamp())
                """)) {audit.setObject(1,actor);audit.setObject(2,target);audit.setString(3,action.equals("grant")?"granted":"revoked");audit.setObject(4,changed);audit.executeUpdate();}
            connection.commit();
        }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        finally{connection.setAutoCommit(true);}
    }
}
