package org.notesknowledge.moderation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.identity.PrivilegeAuthorizationApi;
import org.notesknowledge.websupport.ScopedCursorPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static org.notesknowledge.moderation.ModerationContract.*;

@Repository
class ReportRepository {
    private final ObjectProvider<JdbcClient> clients;
    ReportRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    private JdbcClient jdbc(){return clients.getObject();}
    Report find(UUID id,boolean lock){return jdbc().sql("select * from moderation.report where report_id=:id"+(lock?" for update":""))
        .param("id",id).query(ReportRepository::map).optional().orElseThrow(ModerationContract::missing);}
    void insert(UUID id,UUID target,long generation,UUID reporter,Intake input,Instant now){
        int count=jdbc().sql("""
            insert into moderation.report(report_id,publication_id,publication_generation,reporter_user_id,category,description,state,submitted_at)
            values(:id,:publication,:generation,:reporter,:category,:description,'open',:now)
            on conflict (publication_id,publication_generation,reporter_user_id) where state in ('open','under_review') do nothing
            """).param("id",id).param("publication",target).param("generation",generation).param("reporter",reporter)
            .param("category",input.category()).param("description",input.description().strip()).param("now",Timestamp.from(now)).update();
        if(count!=1)throw conflict();
    }
    List<Report> page(PrivilegeAuthorizationApi.ReportScope scope,String state,String category,ScopedCursorPage.Position position,int count) {
        String sql="select * from moderation.report where true"+(scope.all()?"":" and report_id in (:ids)")
            +(state==null?"":" and state=:state")+(category==null?"":" and category=:category")
            +(position.before()==null?"":" and (submitted_at,report_id)<(:before,:id)")+" order by submitted_at desc,report_id desc limit :count";
        var q=jdbc().sql(sql).param("count",count);
        if(!scope.all())q=q.param("ids",scope.reportIds());if(state!=null)q=q.param("state",state);if(category!=null)q=q.param("category",category);
        if(position.before()!=null)q=q.param("before",Timestamp.from(position.before())).param("id",position.id());return q.query(ReportRepository::map).list();
    }
    void begin(Report r,boolean available,Instant now){jdbc().sql("update moderation.report set state=:state,reviewed_at=:reviewed,resolved_at=:resolved where report_id=:id and state='open'")
        .param("id",r.id()).param("state",available?"under_review":"closed").param("reviewed",available?Timestamp.from(now):null).param("resolved",available?null:Timestamp.from(now)).update();}
    void finish(Report r,UUID actor,UUID id,UUID correlation,DecisionRequest input,Instant now){
        String outcome=input.consequence()==Consequence.none?"dismissed":"actioned";
        jdbc().sql("""
            insert into moderation.moderation_decision(moderation_decision_id,report_id,publication_id,actor_user_id,outcome_code,consequence,reason_code,evidence_generation,correlation_id,occurred_at)
            values(:id,:report,:publication,:actor,:outcome,:consequence,:reason,:generation,:correlation,:now)
            """).param("id",id).param("report",r.id()).param("publication",r.publicationId()).param("actor",actor).param("outcome",outcome)
            .param("consequence",input.consequence().name()).param("reason",input.reasonCode()).param("generation",r.generation()).param("correlation",correlation).param("now",Timestamp.from(now)).update();
        if(jdbc().sql("update moderation.report set state=:state,resolved_at=:now where report_id=:id and state='under_review'")
            .param("state",outcome).param("now",Timestamp.from(now)).param("id",r.id()).update()!=1)throw conflict();
    }
    void audit(Report r,UUID actor,UUID decision,UUID correlation,String action,String reason,Instant now){
        jdbc().sql("""
            insert into moderation.moderation_audit_fact(audit_fact_id,report_id,publication_id,actor_user_id,moderation_decision_id,action_code,outcome_code,reason_code,correlation_id,occurred_at)
            values(uuidv7(),:report,:publication,:actor,:decision,:action,'committed',:reason,:correlation,:now)
            """).param("report",r.id()).param("publication",r.publicationId()).param("actor",actor).param("decision",decision).param("action",action)
            .param("reason",reason).param("correlation",correlation).param("now",Timestamp.from(now)).update();
    }
    Decision decision(UUID report){return jdbc().sql("select * from moderation.moderation_decision where report_id=:id").param("id",report)
        .query((r,i)->{var c=Consequence.valueOf(r.getString("consequence"));return new Decision(r.getObject("moderation_decision_id",UUID.class),report,c,r.getString("reason_code"),
            new Outcomes(c==Consequence.none?"unchanged":"removed",c==Consequence.removePublicationAndSuspendResponsibleAccount?"suspended":"unchanged"),r.getTimestamp("occurred_at").toInstant());}).optional().orElse(null);}
    List<Audit> audits(UUID report){return jdbc().sql("select action_code,outcome_code,reason_code,occurred_at from moderation.moderation_audit_fact where report_id=:id order by occurred_at,audit_fact_id limit 100")
        .param("id",report).query((r,i)->new Audit(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant())).list();}
    private static Report map(ResultSet r,int i)throws SQLException{return new Report(r.getObject("report_id",UUID.class),r.getObject("publication_id",UUID.class),r.getLong("publication_generation"),r.getString("category"),r.getString("description"),r.getString("state"),r.getTimestamp("submitted_at").toInstant(),instant(r,"reviewed_at"),instant(r,"resolved_at"));}
    private static Instant instant(ResultSet r,String name)throws SQLException {var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
}
