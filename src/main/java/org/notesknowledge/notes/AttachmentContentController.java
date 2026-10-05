package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AttachmentContentController {
    private final StreamAttachmentContentService content;
    AttachmentContentController(StreamAttachmentContentService content) { this.content = content; }

    @GetMapping("/api/notes/{noteId}/attachments/{attachmentId}/content")
    void content(@PathVariable UUID noteId, @PathVariable UUID attachmentId,
            HttpServletRequest request, HttpServletResponse response) {
        content.stream(NotesActor.owner(), noteId, attachmentId, request, response);
    }
}
