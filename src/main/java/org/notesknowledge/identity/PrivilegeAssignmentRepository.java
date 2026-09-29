package org.notesknowledge.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads current persisted assignments on every privilege decision. */
@Repository
@IdentityCoreEnabled
class PrivilegeAssignmentRepository {
    private final JdbcClient jdbc;

    PrivilegeAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PrivilegeAssignment> activeFor(UUID userId, ModerationCapability capability,
            ModerationScope scope) {
        return jdbc.sql("""
                select pa.privilege_assignment_id, pa.user_id, pa.scope_id,
                       pa.assigned_by_user_id, pa.granted_at, pa.revoked_at
                from identity.privilege_assignment pa
                join identity.account a on a.user_id = pa.user_id
                where pa.user_id = :userId and pa.capability_code = :capability
                  and pa.scope_kind = 'public_report' and pa.revoked_at is null
                  and pa.granted_at <= current_timestamp
                  and (pa.scope_id is null or pa.scope_id = :reportId)
                  and a.account_state = 'active' and a.email_verified_at is not null
                limit 1
                """).param("userId", userId).param("capability", capability.code())
                .param("reportId", scope.reportId())
                .query((rs, row) -> new PrivilegeAssignment(
                        rs.getObject("privilege_assignment_id", UUID.class),
                        rs.getObject("user_id", UUID.class), capability,
                        new ModerationScope(rs.getObject("scope_id", UUID.class)),
                        rs.getObject("assigned_by_user_id", UUID.class),
                        rs.getTimestamp("granted_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null
                                : rs.getTimestamp("revoked_at").toInstant()))
                .optional();
    }
}
