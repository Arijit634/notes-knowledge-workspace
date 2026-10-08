package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.PublicExposureCoordinator;
import org.notesknowledge.discovery.PublicProjectionApi;
import org.notesknowledge.security.PublicReadRateControl;
import org.notesknowledge.websupport.CursorPage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class PublicationController {
    record Selection(List<UUID> selectedAttachmentIds) { }
    record PublishRequest(List<UUID> selectedAttachmentIds,String previewFingerprint) {
        @Override public String toString(){return "PublishRequest[REDACTED]";}
    }
    private final PublicationService service;
    private final PublicationTransactions transactions;
    private final PublicationRepository repository;
    private final PublicMediaService media;
    private final PublicReadRateControl rate;
    private final PublicExposureCoordinator exposure;
    private final PublicProjectionApi discovery;
    PublicationController(PublicationService service,PublicationTransactions transactions,PublicationRepository repository,
        PublicMediaService media,PublicReadRateControl rate,PublicExposureCoordinator exposure,PublicProjectionApi discovery) {
        this.service=service;this.transactions=transactions;this.repository=repository;this.media=media;this.rate=rate;this.exposure=exposure;this.discovery=discovery;
    }
    @PostMapping("/api/notes/{noteId}/publication-preview")
    ResponseEntity<PublicationService.Preview> preview(@PathVariable UUID noteId,@RequestHeader(value="If-Match",required=false) String tag,
        @RequestBody Selection selection,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.preview(PublishingActor.owner(),noteId,tag,selection.selectedAttachmentIds(),request));
    }
    @PostMapping("/api/notes/{noteId}/publication")
    ResponseEntity<PublicationRecord.OwnerView> create(@PathVariable UUID noteId,@RequestHeader(value="If-Match",required=false) String tag,
        @RequestBody PublishRequest body,HttpServletRequest request) {
        var result=service.create(PublishingActor.owner(),noteId,tag,body.selectedAttachmentIds(),body.previewFingerprint(),request);
        return ResponseEntity.created(URI.create("/api/me/publications/"+result.view().id())).header("Cache-Control","no-store").eTag(result.etag()).body(result.view());
    }
    @GetMapping("/api/me/publications")
    ResponseEntity<CursorPage<PublicationRecord.OwnerSummary>> list(@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(transactions.page(PublishingActor.owner(),limit,cursor,request));
    }
    @GetMapping("/api/me/publications/{id}")
    ResponseEntity<PublicationRecord.OwnerView> read(@PathVariable UUID id,HttpServletRequest request){return ok(transactions.read(PublishingActor.owner(),id,request));}
    @GetMapping("/api/me/publications/{id}/source-status")
    ResponseEntity<PublicationTransactions.SourceStatus> status(@PathVariable UUID id,HttpServletRequest request){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(transactions.status(PublishingActor.owner(),id,request));
    }
    @PutMapping("/api/me/publications/{id}")
    ResponseEntity<PublicationRecord.OwnerView> update(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String tag,
        @RequestBody PublishRequest body,HttpServletRequest request){return ok(service.replace(PublishingActor.owner(),id,tag,body.selectedAttachmentIds(),body.previewFingerprint(),"update",request));}
    @PostMapping("/api/me/publications/{id}/republish")
    ResponseEntity<PublicationRecord.OwnerView> republish(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String tag,
        @RequestBody PublishRequest body,HttpServletRequest request){return ok(service.replace(PublishingActor.owner(),id,tag,body.selectedAttachmentIds(),body.previewFingerprint(),"republish",request));}
    @PostMapping("/api/me/publications/{id}/unpublish")
    ResponseEntity<PublicationRecord.OwnerView> unpublish(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String tag,HttpServletRequest request){
        return ok(service.unpublish(PublishingActor.owner(),id,tag,request));
    }
    @GetMapping("/api/public/publications/{id}")
    ResponseEntity<PublicationTransactions.PublicView> publicRead(@PathVariable UUID id,HttpServletRequest request) {
        rate.check(request);var initial=repository.active(id).orElseThrow(PublicationTransactions::missing);
        PublicationTransactions.PublicView result;
        try(var lease=exposure.readLease(initial.owner())){result=transactions.publicView(id);}
        try{discovery.recordApproximateView(id,initial.generation());}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PublicationController.class).warn("public_view_aggregate_deferred");}
        org.slf4j.LoggerFactory.getLogger(PublicationController.class).debug("public_publication_read outcome=success");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
    }
    @GetMapping("/api/public/publications/{id}/media/{mediaId}/content")
    void content(@PathVariable UUID id,@PathVariable UUID mediaId,HttpServletRequest request,HttpServletResponse response){
        rate.check(request);media.stream(id,mediaId,request,response);
    }
    private static ResponseEntity<PublicationRecord.OwnerView> ok(PublicationTransactions.Etagged result){
        return ResponseEntity.ok().header("Cache-Control","no-store").eTag(result.etag()).body(result.view());
    }
}
