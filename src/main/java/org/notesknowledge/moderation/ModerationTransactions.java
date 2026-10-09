package org.notesknowledge.moderation;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.*;
import org.notesknowledge.publishing.PublicationModerationApi;
import org.notesknowledge.websupport.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.notesknowledge.moderation.ModerationContract.*;

@Service
class ModerationTransactions {
    record Target(UUID publication,UUID responsible) { }
    private final ReportRepository reports;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final ObjectProvider<RecentAuthenticationApi> recent;
    private final ObjectProvider<PrivilegeAuthorizationApi> privileges;
    private final ObjectProvider<AccountSuspensionApi> suspension;
    private final PublicationModerationApi publications;
    private final OpaqueCursorCodec cursors;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final ModerationTelemetry telemetry;
    ModerationTransactions(ReportRepository reports,ObjectProvider<AccountEligibilityApi> accounts,ObjectProvider<RecentAuthenticationApi> recent,
        ObjectProvider<PrivilegeAuthorizationApi> privileges,ObjectProvider<AccountSuspensionApi> suspension,PublicationModerationApi publications,
        OpaqueCursorCodec cursors,DatabaseUuidV7Generator ids,Clock clock,ModerationTelemetry telemetry) {
        this.reports=reports;this.accounts=accounts;this.recent=recent;this.privileges=privileges;this.suspension=suspension;
        this.publications=publications;this.cursors=cursors;this.ids=ids;this.clock=clock;this.telemetry=telemetry;
    }
    @Transactional(timeout=5)
    Receipt submit(UUID actor,UUID publication,Intake input,HttpServletRequest request) {
        input.validate();UUID owner=publications.responsibleAccount(publication);
        suspension.getObject().lockParticipants(actor,owner);accounts.getObject().requireCurrentOwner(actor,request);
        var evidence=publications.current(publication,true);if(evidence==null)throw missing();
        UUID id=ids.generate();var now=now();reports.insert(id,publication,evidence.generation(),actor,input,now);
        telemetry.committed("submit");return new Receipt(id,publication,input.category(),input.description().strip(),"open",now);
    }
    @Transactional(timeout=5)
    CursorPage<Report> queue(UUID actor,String state,String category,String sort,Integer requested,String cursor,HttpServletRequest request) {
        if(state!=null&&!STATES.contains(state)||category!=null&&!CATEGORIES.contains(category)||!"submittedDesc".equals(sort))throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        var scope=authorize(actor,ModerationCapability.REVIEW,null,request);
        var paging=new ScopedCursorPage(cursors,"MODERATION_REPORTS",actor+":"+scope,"state="+state+";category="+category,"SUBMITTED_DESC");
        int limit=new PageLimitPolicy(20,100).resolve(requested);var rows=reports.page(scope,state,category,paging.position(cursor),limit+1);
        boolean more=rows.size()>limit;var visible=more?rows.subList(0,limit):rows;
        return new CursorPage<>(visible,more?paging.next(visible.getLast().submittedAt(),visible.getLast().id()):null);
    }
    @Transactional(timeout=5)
    Detail detail(UUID actor,UUID report,HttpServletRequest request) {
        authorize(actor,ModerationCapability.REVIEW,report,request);return detail(reports.find(report,false));
    }
    @Transactional(timeout=5)
    Detail begin(UUID actor,UUID report,HttpServletRequest request) {
        authorize(actor,ModerationCapability.REVIEW,report,request);
        var r=reports.find(report,true);
        if(r.status().equals("under_review"))return detail(r);
        if(!r.status().equals("open"))throw conflict();
        var evidence=publications.current(r.publicationId(),false);
        boolean available=evidence!=null&&evidence.generation()==r.generation();
        var now=now();reports.begin(r,available,now);reports.audit(r,actor,null,ids.generate(),available?"begin_review":"close_unavailable",available?"review_started":"target_unavailable",now);
        telemetry.committed("begin");return detail(reports.find(report,false));
    }
    /** No authority survives preflight: the action rechecks it inside its consistency boundary. */
    @Transactional(timeout=5)
    Target preflight(UUID actor,UUID report,HttpServletRequest request) {
        authorize(actor,ModerationCapability.ENFORCE,report,request);
        var r=reports.find(report,false);return new Target(r.publicationId(),publications.responsibleAccount(r.publicationId()));
    }
    @Transactional(timeout=5)
    Decision decide(UUID actor,UUID report,Target target,DecisionRequest input,HttpServletRequest request) {
        input.validate();
        // Account UUID order -> actor session -> assignment -> public exposure -> Publication -> Report.
        // Owner update/unpublish/deletion also lock the responsible Account before Publication.
        suspension.getObject().lockParticipants(actor,target.responsible());
        authorize(actor,ModerationCapability.ENFORCE,report,request);
        var evidence=input.consequence()==Consequence.none?null:publications.current(target.publication(),true);
        var r=reports.find(report,true);
        if(!r.publicationId().equals(target.publication())||!r.status().equals("under_review"))throw conflict();
        UUID correlation=ids.generate(),decision=ids.generate();
        if(input.consequence()!=Consequence.none) {
            if(evidence==null||evidence.generation()!=r.generation())throw conflict();
            publications.remove(r.publicationId(),r.generation(),actor);
            if(input.consequence()==Consequence.removePublicationAndSuspendResponsibleAccount)
                suspension.getObject().suspendResponsible(actor,target.responsible(),r.publicationId(),correlation);
        }
        var now=now();reports.finish(r,actor,decision,correlation,input,now);
        reports.audit(r,actor,decision,correlation,"decision",input.reasonCode(),now);
        if(input.consequence()!=Consequence.none)reports.audit(r,actor,decision,correlation,"public_removal",input.reasonCode(),now);
        if(input.consequence()==Consequence.removePublicationAndSuspendResponsibleAccount)reports.audit(r,actor,decision,correlation,"account_suspension",input.reasonCode(),now);
        telemetry.committed("decision");return reports.decision(report);
    }
    private PrivilegeAuthorizationApi.ReportScope authorize(UUID actor,ModerationCapability capability,UUID report,HttpServletRequest request) {
        accounts.getObject().requireCurrentOwner(actor,request);recent.getObject().requireRecent(actor,request);
        var scope=privileges.getObject().lockCurrentScope(actor,capability);
        if(report!=null&&!scope.all()&&!scope.reportIds().contains(report))throw missing();return scope;
    }
    private Detail detail(Report r) {
        var evidence=publications.current(r.publicationId(),false);
        boolean available=evidence!=null&&evidence.generation()==r.generation();
        return new Detail(r,available?evidence:null,available,reports.decision(r.id()),reports.audits(r.id()));
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
}
