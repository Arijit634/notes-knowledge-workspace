package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.discovery.PublicProjectionApi;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.RecentAuthenticationApi;
import org.notesknowledge.notes.AttachmentSourceApi;
import org.notesknowledge.notes.PublishableSourceApi;
import org.notesknowledge.profile.PublicProfileApi;
import org.notesknowledge.websupport.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicationTransactions {
    record Prepared(UUID owner,PublishableSourceApi.Source source,List<AttachmentSourceApi.Selected> selection,PublicProfileApi.Author author) {
        @Override public String toString(){return "PreparedPublication[REDACTED]";}
    }
    record Etagged(PublicationRecord.OwnerView view,String etag) { }
    record Committed(Etagged result,List<String> retired) { }
    record SourceStatus(boolean sourceExists,boolean sourceUsable,boolean driftedSincePublication,boolean updatePublicCopyReady) { }
    record PublicView(UUID id,String title,String markdown,List<String> tags,PublicProfileApi.View author,
        List<PublicationRecord.MediaView> media,PublicProjectionApi.Engagement engagement,java.time.Instant publishedAt) {
        @Override public String toString(){return "PublicPublicationView[REDACTED]";}
    }
    private final PublicationRepository repository;
    private final PublishableSourceApi notes;
    private final AttachmentSourceApi attachments;
    private final PublicProfileApi profiles;
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final ObjectProvider<RecentAuthenticationApi> recent;
    private final boolean recentRequired;
    private final PublicProjectionApi discovery;
    private final PublicExposureCoordinator exposure;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final PublicationPreviewFingerprintCodec fingerprints;
    private final OpaqueCursorCodec cursors;
    private final PublicationTelemetry telemetry;
    PublicationTransactions(PublicationRepository repository,PublishableSourceApi notes,AttachmentSourceApi attachments,
        PublicProfileApi profiles,ObjectProvider<AccountEligibilityApi> eligibility,ObjectProvider<RecentAuthenticationApi> recent,
        @Value("${publishing.recent-auth-required:true}") boolean recentRequired,PublicProjectionApi discovery,
        PublicExposureCoordinator exposure,DatabaseUuidV7Generator ids,Clock clock,StrongCoreEtagCodec etags,
        IfMatchPrecondition preconditions,PublicationPreviewFingerprintCodec fingerprints,OpaqueCursorCodec cursors,PublicationTelemetry telemetry) {
        this.repository=repository;this.notes=notes;this.attachments=attachments;this.profiles=profiles;this.eligibility=eligibility;
        this.recent=recent;this.recentRequired=recentRequired;this.discovery=discovery;this.exposure=exposure;this.ids=ids;
        this.clock=clock;this.etags=etags;this.preconditions=preconditions;this.fingerprints=fingerprints;this.cursors=cursors;this.telemetry=telemetry;
    }
    @Transactional
    Prepared preview(UUID owner,UUID note,String ifMatch,List<UUID> selection,HttpServletRequest request) {
        authorize(owner,request,true);
        return new Prepared(owner,notes.preview(owner,note,ifMatch),attachments.requireValidatedSelection(owner,note,selection),profiles.requireActiveProjection(owner));
    }
    @Transactional
    Prepared prepareReplacement(UUID owner,UUID id,String ifMatch,List<UUID> selection,HttpServletRequest request) {
        authorize(owner,request,true);
        var current=requireOwner(owner,id,false);
        preconditions.requireCurrent(ifMatch,etag(current));
        var source=notes.sourceStatus(owner,current.note(),current.sourceRevision());
        if(!source.updateReady())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        // Note revision is read under its own owner lock, not trusted from the browser.
        var locked=notes.current(owner,current.note());
        return new Prepared(owner,locked,attachments.requireValidatedSelection(owner,current.note(),selection),profiles.requireActiveProjection(owner));
    }
    @Transactional
    Committed commit(Prepared captured,UUID id,boolean create,String action,String ifMatch,String fingerprint,
        List<PublicationRecord.Media> media,HttpServletRequest request) {
        authorize(captured.owner(),request,true);
        var source=notes.requireCurrent(captured.owner(),captured.source().noteId(),captured.source().revision());
        var selected=attachments.requireValidatedSelection(captured.owner(),source.noteId(),captured.selection().stream().map(AttachmentSourceApi.Selected::id).toList());
        var author=profiles.requireActiveProjection(captured.owner());
        var current=new Prepared(captured.owner(),source,selected,author);
        if(!current.equals(captured))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        fingerprints.require(fingerprint,current);
        exposure.denyBoundary(captured.owner());
        PublicationRecord old=null;List<String> retired=List.of();
        if(create) {
            if(repository.source(captured.owner(),source.noteId()).isPresent())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        } else {
            old=requireOwner(captured.owner(),id,true);preconditions.requireCurrent(ifMatch,etag(old));
            if(!old.note().equals(source.noteId())||!(action.equals("update")&&old.state().equals("active")
                ||action.equals("republish")&&old.state().equals("unpublished")))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
            retired=repository.media(id).stream().map(PublicationRecord.Media::reference).toList();
        }
        var checkpoint=notes.checkpointAndHold(captured.owner(),source,id);var now=now();
        if(create)repository.create(id,current,checkpoint,now);
        else repository.replace(old,current,checkpoint,action.equals("update")?"owner_update":"owner_republish",now);
        long snapshot=create?1:old.snapshot()+1,generation=create?1:old.generation()+1;
        repository.children(id,snapshot,generation,source.tags(),media,now);
        var saved=requireOwner(captured.owner(),id,false);
        discovery.advance(new PublicProjectionApi.Snapshot(id,author.projectionId(),generation,saved.title(),saved.markdown(),saved.tags(),saved.publishedAt(),saved.updatedAt()));
        repository.audit(ids.generate(),saved,create?"create":action,create?"owner_publish":action.equals("update")?"owner_update":"owner_republish",now);
        if(old!=null){
            if(!old.checkpoint().equals(checkpoint))notes.releaseHold(old.owner(),old.note(),old.checkpoint(),id);
            telemetry.denialAfterCommit(PublicationTelemetry.Denial.SUPERSEDED);
        }
        return new Committed(etagged(saved),retired);
    }
    @Transactional
    Committed unpublish(UUID owner,UUID id,String ifMatch,HttpServletRequest request) {
        authorize(owner,request,false);exposure.denyBoundary(owner);
        var current=requireOwner(owner,id,true);preconditions.requireCurrent(ifMatch,etag(current));
        if(!current.state().equals("active"))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        var retired=repository.media(id).stream().map(PublicationRecord.Media::reference).toList();
        deny(current,"owner_unpublish","unpublish");
        return new Committed(etagged(requireOwner(owner,id,false)),retired);
    }
    @Transactional
    Etagged read(UUID owner,UUID id,HttpServletRequest request){authorize(owner,request,false);return etagged(requireOwner(owner,id,false));}
    @Transactional
    SourceStatus status(UUID owner,UUID id,HttpServletRequest request){
        authorize(owner,request,false);var p=requireOwner(owner,id,false);var source=notes.sourceStatus(owner,p.note(),p.sourceRevision());
        return new SourceStatus(source.sourceExists(),source.updateReady(),source.hasChanges(),source.updateReady());
    }
    @Transactional
    CursorPage<PublicationRecord.OwnerSummary> page(UUID owner,Integer requested,String cursor,HttpServletRequest request) {
        authorize(owner,request,false);int limit=new PageLimitPolicy(20,100).resolve(requested);
        var paging=new ScopedCursorPage(cursors,"OWNER_PUBLICATIONS",owner.toString(),"ALL","UPDATED_DESC");
        var pos=paging.position(cursor);var rows=repository.page(owner,pos.before(),pos.id(),limit+1);
        boolean more=rows.size()>limit;var visible=more?rows.subList(0,limit):rows;
        return new CursorPage<>(visible,more?paging.next(visible.getLast().updatedAt(),visible.getLast().id()):null);
    }
    @Transactional(readOnly=true)
    PublicView publicView(UUID id) {
        var p=repository.active(id).orElseThrow(PublicationTransactions::missing);
        var author=profiles.resolveById(p.author());
        return new PublicView(p.id(),p.title(),p.markdown(),p.tags(),author.view(),repository.media(id).stream()
            .filter(m->m.snapshot()==p.snapshot()&&m.generation()==p.generation()).map(PublicationRecord.Media::view).toList(),
            discovery.engagement(id,p.generation()),p.publishedAt());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    boolean hasActiveSource(UUID owner,UUID note){return repository.source(owner,note).filter(p->p.state().equals("active")).isPresent();}
    @Transactional(propagation=Propagation.MANDATORY)
    void retireSource(UUID owner,UUID note) {
        exposure.denyBoundary(owner);
        repository.source(owner,note).filter(p->p.state().equals("active")).ifPresent(p->deny(requireOwner(owner,p.id(),true),"source_retired","source_retired"));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    void retireAccount(UUID owner) {
        exposure.denyBoundary(owner);
        // Denial runs in the caller's local transaction, but never materializes an unbounded account corpus.
        List<UUID> batch;
        do {
            batch=repository.activeOwner(owner);
            for(var id:batch)deny(requireOwner(owner,id,true),"account_deleted","account_deleted");
        }while(batch.size()==100);
    }
    private void deny(PublicationRecord p,String reason,String action) {
        var now=now();repository.deny(p,reason,now);
        var saved=requireOwner(p.owner(),p.id(),false);discovery.invalidate(p.id(),saved.generation(),now);
        repository.audit(ids.generate(),saved,action,reason,now);notes.releaseHold(p.owner(),p.note(),p.checkpoint(),p.id());
        telemetry.denialAfterCommit(switch(reason){case "source_retired"->PublicationTelemetry.Denial.SOURCE_RETIRED;
            case "account_deleted"->PublicationTelemetry.Denial.ACCOUNT_DELETED;default->PublicationTelemetry.Denial.OWNER_UNPUBLISH;});
    }
    private void authorize(UUID owner,HttpServletRequest request,boolean publicationMutation) {
        eligibility.getObject().requireCurrentOwner(owner,request);
        if(publicationMutation&&recentRequired)recent.getObject().requireRecent(owner,request);
    }
    private PublicationRecord requireOwner(UUID owner,UUID id,boolean lock){return repository.owner(owner,id,lock).orElseThrow(PublicationTransactions::missing);}
    private Etagged etagged(PublicationRecord p){return new Etagged(view(p),etag(p));}
    private PublicationRecord.OwnerView view(PublicationRecord p){return new PublicationRecord.OwnerView(p.id(),p.title(),p.markdown(),p.tags(),p.state(),"/p/"+p.id(),
        repository.media(p.id()).stream().map(PublicationRecord.Media::view).toList(),p.publishedAt(),p.updatedAt());}
    private String etag(PublicationRecord p){return etags.encode(new PublicationCoreVersion(p.id(),p.snapshot(),p.generation()));}
    private java.time.Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
