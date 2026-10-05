package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.CursorPage;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/notes/{noteId}/attachments")
class AttachmentController {
    private final AttachmentUploadService uploads;
    private final ReadAttachmentQuery reads;
    private final StrongCoreEtagCodec etags;
    AttachmentController(AttachmentUploadService uploads, ReadAttachmentQuery reads, StrongCoreEtagCodec etags) {
        this.uploads = uploads; this.reads = reads; this.etags = etags;
    }

    @GetMapping
    ResponseEntity<CursorPage<AttachmentView>> list(@PathVariable UUID noteId, @RequestParam Map<String, String> query) {
        UUID owner = NotesActor.owner();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reads.page(owner, noteId, query));
        } catch (DataAccessException | TransactionException failure) { throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    }

    @GetMapping("/{attachmentId}")
    AttachmentView read(@PathVariable UUID noteId, @PathVariable UUID attachmentId, HttpServletResponse response) {
        UUID owner = NotesActor.owner();
        try {
            var core = reads.read(owner, noteId, attachmentId);
            // Direct response-body handling avoids ResponseEntity's automatic conditional GET/304 path.
            // This ETag is a core mutation validator, not a selected cache-revalidation contract.
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.ETAG, etags.encode(new AttachmentCoreVersion(core.view().id(), core.revision())));
            return core.view();
        } catch (DataAccessException | TransactionException failure) { throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AttachmentView> upload(@PathVariable UUID noteId, HttpServletRequest request) {
        UUID owner = NotesActor.owner();
        var multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        var files = multipart.getMultiFileMap();
        if (files.size() != 1 || !files.containsKey("file") || files.get("file").size() != 1
                || !request.getParameterMap().isEmpty()) throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        try {
            var view = uploads.upload(owner, noteId, request, files.get("file").getFirst());
            return ResponseEntity.created(URI.create("/api/notes/" + noteId + "/attachments/" + view.id()))
                    .cacheControl(CacheControl.noStore()).eTag(etags.encode(new AttachmentCoreVersion(view.id(), 1))).body(view);
        } catch (DataAccessException | TransactionException failure) { throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    }
}
