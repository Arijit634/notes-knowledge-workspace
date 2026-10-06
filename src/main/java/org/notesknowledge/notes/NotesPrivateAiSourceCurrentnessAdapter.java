package org.notesknowledge.notes;

import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.MANDATORY)
class NotesPrivateAiSourceCurrentnessAdapter implements PrivateAiSourceCurrentness {
    private final ObjectProvider<JdbcClient> clients;
    NotesPrivateAiSourceCurrentnessAdapter(ObjectProvider<JdbcClient> clients) { this.clients=clients; }
    public boolean matches(Expected e) {
        // revision belongs to the selected source kind; the parent Note always supplies the inherited AI generation.
        var sql=clients.getObject().sql("select n.note_id from notes.note n where n.note_id=:note "
                +"and n.owner_user_id=:owner and n.lifecycle_state in ('active','archived') and n.ai_enabled "
                +(e.attachmentId()==null?"and n.revision=:revision ":"")+"and n.ai_generation=:generation for share")
                .param("note",e.noteId()).param("owner",e.owner()).param("generation",e.aiGeneration());
        if(e.attachmentId()==null) sql.param("revision",e.revision());
        if(sql.query(java.util.UUID.class).optional().isEmpty()) return false;
        if(e.attachmentId()==null) return true;
        return clients.getObject().sql("select attachment_id from notes.attachment where attachment_id=:attachment "
                +"and note_id=:note and owner_user_id=:owner and revision=:revision and processing_generation=:attachmentGeneration "
                +"and storage_state='stored' and validation_state='accepted' and cleanup_state='retained' "
                +"and removed_at is null for share")
                .param("attachment",e.attachmentId()).param("note",e.noteId()).param("owner",e.owner())
                .param("revision",e.revision()).param("attachmentGeneration",e.attachmentGeneration()).query(java.util.UUID.class).optional().isPresent();
    }
}
