package org.notesknowledge.identity;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Identity owns eligibility locks and the narrow, attributable moderation consequence. */
@Service
@IdentityCoreEnabled
@Transactional(propagation=Propagation.MANDATORY)
public class AccountSuspensionApi {
    private final JdbcClient jdbc;
    private final SpringSessionAuthorityAdapter sessions;
    private final PublicExposureCoordinator exposure;
    private final org.notesknowledge.identity.spi.ModerationAccountSubject subjects;
    AccountSuspensionApi(JdbcClient jdbc,SpringSessionAuthorityAdapter sessions,PublicExposureCoordinator exposure,
        org.notesknowledge.identity.spi.ModerationAccountSubject subjects) {
        this.jdbc=jdbc;this.sessions=sessions;this.exposure=exposure;this.subjects=subjects;
    }
    /** Must precede any session, public-exposure, Publication or Report lock. */
    public void lockParticipants(UUID actor,UUID responsible) {
        var subjects=java.util.stream.Stream.of(actor,responsible).distinct().sorted().toList();
        var locked=jdbc.sql("select user_id from identity.account where user_id in (:ids) order by user_id for update")
            .param("ids",subjects).query(UUID.class).list();
        if(locked.size()!=subjects.size())throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
    }
    public void suspendResponsible(UUID actor,UUID responsible,UUID publication,UUID correlation) {
        // Caller holds the dispatch owner boundary and ordered participant locks.
        if(!subjects.isResponsibleForRemovedPublication(responsible,publication))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        exposure.denyBoundary(responsible);
        if(jdbc.sql("update identity.account set account_state='suspended',updated_at=greatest(updated_at,clock_timestamp()) where user_id=:id and account_state='active' and email_verified_at is not null")
            .param("id",responsible).update()!=1)throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        sessions.revokeAll(responsible);
        jdbc.sql("""
            insert into identity.security_audit_fact(audit_fact_id,actor_user_id,target_user_id,event_category,outcome_code,correlation_id,occurred_at)
            values(uuidv7(),:actor,:target,'moderation_suspension','suspended',:correlation,clock_timestamp())
            """).param("actor",actor).param("target",responsible).param("correlation",correlation).update();
    }
}
