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
    private final tools.jackson.databind.ObjectMapper expectedJson=new tools.jackson.databind.ObjectMapper();
    NotesPrivateAiSourceCurrentnessAdapter(ObjectProvider<JdbcClient> clients) { this.clients=clients; }
    public java.util.List<Expected> matching(java.util.List<Expected> expected) {
        if(expected.isEmpty())return java.util.List.of();
        if(expected.size()>MAX_BATCH)throw new IllegalArgumentException("Invalid currentness batch");
        var owner=expected.getFirst().owner();
        if(expected.stream().anyMatch(e->!owner.equals(e.owner())))return java.util.List.of();
        String payload=expectedJson.writeValueAsString(expected);
        var query=clients.getObject().sql("""
            with expected as materialized (
                select * from jsonb_to_recordset(:expected::jsonb) as e(owner uuid,"noteId" uuid,"attachmentId" uuid,
                    revision bigint,"aiGeneration" bigint,"attachmentGeneration" bigint))
            select e.* from expected e join notes.note n on n.note_id=e."noteId" and n.owner_user_id=e.owner
            left join notes.attachment a on a.attachment_id=e."attachmentId" and a.note_id=n.note_id and a.owner_user_id=n.owner_user_id
            where n.owner_user_id=:owner and n.lifecycle_state in ('active','archived') and n.ai_enabled
                and n.ai_generation=e."aiGeneration" and (
                    (e."attachmentId" is null and n.revision=e.revision) or
                    (e."attachmentId" is not null and a.revision=e.revision and a.processing_generation=e."attachmentGeneration"
                        and a.storage_state='stored' and a.validation_state='accepted' and a.cleanup_state='retained' and a.removed_at is null))
            order by n.note_id,e."attachmentId" nulls first for share of n
            """).param("expected",payload).param("owner",owner)
            .query((r,i)->new Expected(owner,r.getObject("noteId",java.util.UUID.class),r.getObject("attachmentId",java.util.UUID.class),
                r.getLong("revision"),r.getLong("aiGeneration"),r.getObject("attachmentGeneration",Long.class)));
        var result=query.list();
        var media=result.stream().map(Expected::attachmentId).filter(java.util.Objects::nonNull).distinct().toList();
        if(!media.isEmpty())clients.getObject().sql("select attachment_id from notes.attachment where owner_user_id=:owner and attachment_id in (:media) order by attachment_id for share")
            .param("owner",owner).param("media",media).query(java.util.UUID.class).list();
        // Resolve again after Attachment locks: a concurrent removal may have committed before acquisition.
        return media.isEmpty()?result:query.list();
    }
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
