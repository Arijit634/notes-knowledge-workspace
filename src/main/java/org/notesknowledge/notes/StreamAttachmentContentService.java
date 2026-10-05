package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.ResponseStreamInterruptedException;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class StreamAttachmentContentService {
    private static final Logger LOG = LoggerFactory.getLogger(StreamAttachmentContentService.class);
    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "audio/wav", "video/mp4", "application/pdf");
    private final NotesRepository notes;
    private final AttachmentRepository attachments;
    private final PrivateAttachmentObjectStore store;
    private final StrongCoreEtagCodec etags;

    StreamAttachmentContentService(NotesRepository notes, AttachmentRepository attachments,
            PrivateAttachmentObjectStore store, StrongCoreEtagCodec etags) {
        this.notes = notes; this.attachments = attachments; this.store = store; this.etags = etags;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void stream(UUID owner, UUID note, UUID attachment, HttpServletRequest request, HttpServletResponse response) {
        AttachmentContentDescriptor descriptor;
        try {
            notes.find(owner, note).orElseThrow(StreamAttachmentContentService::missing);
            descriptor = attachments.content(owner, note, attachment).orElseThrow(StreamAttachmentContentService::missing);
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        if (descriptor.size() <= 0 || descriptor.reference() == null
                || !descriptor.reference().matches("private-attachment/[0-9a-f]{64}")) throw missing();
        if (descriptor.mediaType() == null || !TYPES.contains(descriptor.mediaType()) || descriptor.filename() == null
                || descriptor.filename().isEmpty() || descriptor.filename().codePoints().anyMatch(Character::isISOControl)) throw unavailable();
        List<String> rangeHeaders;
        try { rangeHeaders = Collections.list(request.getHeaders(HttpHeaders.RANGE)); }
        catch (RequestRejectedException malformedHeader) { throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
        if (rangeHeaders.size() > 1) throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        var range = MediaRangeParser.parse(rangeHeaders.isEmpty() ? null : rangeHeaders.getFirst(), descriptor.size());
        try (InputStream input = store.openRange(descriptor.reference(), range.start(), range.length())) {
            if (input == null) throw new IOException("unavailable");
            response.setStatus(range.partial() ? 206 : 200);
            response.setContentType(descriptor.mediaType());
            response.setContentLengthLong(range.length());
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.ETAG, etags.encode(new AttachmentCoreVersion(descriptor.id(), descriptor.revision())));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                    .filename(descriptor.filename(), StandardCharsets.UTF_8).build().toString());
            if (range.partial()) response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + descriptor.size());
            byte[] buffer = new byte[16 * 1024];
            long remaining = range.length();
            var output = response.getOutputStream();
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (count <= 0) throw new IOException("unavailable");
                output.write(buffer, 0, count);
                remaining = Math.subtractExact(remaining, count);
            }
        } catch (IOException | RuntimeException failure) {
            if (response.isCommitted()) {
                LOG.warn("attachment_stream_interrupted");
                throw new ResponseStreamInterruptedException(); // No provider cause/private diagnostics.
            }
            resetUncommittedMedia(response);
            throw unavailable();
        }
    }

    private static ApiFailureException missing() { return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND); }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }

    private static void resetUncommittedMedia(HttpServletResponse response) {
        // Reset the servlet's internal length as well as buffered media. Merely
        // deleting Content-Length does not reliably reset container bookkeeping.
        // Preserve existing security/session headers, never their private values in logs.
        var mediaHeaders = Set.of("content-length", "content-range", "content-type", "etag", "accept-ranges", "content-disposition");
        var retained = new LinkedHashMap<String, List<String>>();
        for (String name : response.getHeaderNames()) {
            if (!mediaHeaders.contains(name.toLowerCase(Locale.ROOT))) retained.put(name, List.copyOf(response.getHeaders(name)));
        }
        response.reset();
        retained.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }
}
