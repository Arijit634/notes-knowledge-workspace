package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/me/profile/avatar")
class AvatarController {
    private final AvatarService avatars;
    AvatarController(AvatarService avatars) { this.avatars = avatars; }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ProfileView> replace(HttpServletRequest request) {
        var owner = ProfileController.owner();
        // Keep the outer Spring Session request for authority revalidation. A
        // native multipart request is used only to inspect the upload framing.
        var multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        var files = multipart.getMultiFileMap();
        if (files.size() != 1 || !files.containsKey("file") || files.get("file").size() != 1
                || !request.getParameterMap().isEmpty()) throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(avatars.replace(owner, request, files.get("file").getFirst()));
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @DeleteMapping
    ResponseEntity<Void> remove(HttpServletRequest request) {
        var owner = ProfileController.owner();
        try { avatars.remove(owner, request); }
        catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
