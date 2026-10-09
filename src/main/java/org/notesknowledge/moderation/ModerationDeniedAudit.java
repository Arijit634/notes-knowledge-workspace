package org.notesknowledge.moderation;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rejected attempts have no enforcement effects. Record them only after the rejected
 * action's transaction has unwound, so rolling back enforcement cannot erase the attempt.
 */
@Service
class ModerationDeniedAudit {
    private final ObjectProvider<JdbcClient> clients;
    ModerationDeniedAudit(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    @Transactional(timeout=3)
    void record(UUID actor,UUID report) {
        clients.getObject().sql("""
            insert into moderation.moderation_audit_fact(audit_fact_id,report_id,publication_id,actor_user_id,action_code,outcome_code,reason_code,correlation_id,occurred_at)
            select uuidv7(),report_id,publication_id,:actor,'denied','denied','capability_required',uuidv7(),clock_timestamp()
            from moderation.report where report_id=:report
            """).param("actor",actor).param("report",report).update();
    }
}
