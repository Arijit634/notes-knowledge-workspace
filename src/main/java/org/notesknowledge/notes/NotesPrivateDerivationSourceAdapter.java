package org.notesknowledge.notes;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.MANDATORY)
class NotesPrivateDerivationSourceAdapter implements PrivateDerivationSource {
    private final ObjectProvider<JdbcClient> clients;
    private final NotesPrivateAiSourceCurrentnessAdapter current;
    private final AttachmentRepository attachments;
    private final ObjectProvider<PrivateAttachmentObjectStore> stores;
    NotesPrivateDerivationSourceAdapter(ObjectProvider<JdbcClient> clients,NotesPrivateAiSourceCurrentnessAdapter current,
            AttachmentRepository attachments,ObjectProvider<PrivateAttachmentObjectStore> stores) {
        this.clients=clients;this.current=current;this.attachments=attachments;this.stores=stores;
    }
    private static final String SOURCES="""
        select n.owner_user_id,n.note_id,null::uuid attachment_id,n.revision,n.ai_generation,
            null::bigint processing_generation,'note' modality,n.ai_enabled,n.lifecycle_state,
            null::text media_type,0::bigint size_bytes,null::float8 duration_seconds,null::integer page_count
        from notes.note n where n.lifecycle_state<>'logically_deleted'
        union all
        select n.owner_user_id,n.note_id,a.attachment_id,a.revision,n.ai_generation,a.processing_generation,
            a.media_kind,n.ai_enabled,n.lifecycle_state,a.media_type,a.size_bytes,a.duration_seconds,a.page_count
        from notes.note n join notes.attachment a on a.note_id=n.note_id and a.owner_user_id=n.owner_user_id
        where n.lifecycle_state<>'logically_deleted' and a.cleanup_state='retained'
            and a.storage_state='stored' and a.validation_state='accepted' and a.removed_at is null
        """;
    public Inventory inventory(Cursor after,int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid inventory bound");
        var sql=clients.getObject().sql("select * from ("+SOURCES+") s where "
                +"(:afterNote::uuid is null or (note_id,coalesce(attachment_id,'00000000-0000-0000-0000-000000000000'::uuid)) "
                +"> (:afterNote::uuid,:afterAttachment::uuid)) order by note_id,attachment_id nulls first limit :limit")
            .param("afterNote",after==null?null:after.noteId(),java.sql.Types.OTHER)
            .param("afterAttachment",after==null||after.attachmentId()==null?new UUID(0,0):after.attachmentId(),java.sql.Types.OTHER)
            .param("limit",limit);
        var rows=sql.query(NotesPrivateDerivationSourceAdapter::map).list();
        var last=rows.isEmpty()?null:rows.getLast().expected();
        return new Inventory(rows,rows.size()<limit?null:new Cursor(last.noteId(),last.attachmentId()));
    }
    public List<Metadata> noteSources(UUID owner,UUID noteId) {
        var rows=clients.getObject().sql("select * from ("+SOURCES+") s where owner_user_id=:owner and note_id=:note "
                +"order by attachment_id nulls first limit 21").param("owner",owner).param("note",noteId)
            .query(NotesPrivateDerivationSourceAdapter::map).list();
        if(rows.isEmpty())throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        return rows;
    }
    public Material acquire(PrivateAiSourceCurrentness.Expected e) {
        if(!current.matches(e))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        if(e.attachmentId()==null) {
            String text=clients.getObject().sql("select markdown from notes.note where note_id=:note and owner_user_id=:owner")
                .param("note",e.noteId()).param("owner",e.owner()).query(String.class).single();
            return new Material() { public String markdown(){return text;} public InputStream open(){throw new IllegalStateException("Not media");} };
        }
        var descriptor=attachments.content(e.owner(),e.noteId(),e.attachmentId()).orElseThrow();
        var used=new AtomicBoolean();
        return new Material() {
            public String markdown(){return null;}
            public List<PdfPage> pdfPages(byte[] bytes){return PrivatePdfTextExtractor.extract(bytes);}
            public InputStream open() {
                if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!used.compareAndSet(false,true))throw new IllegalStateException("One-shot media outside transaction required");
                return stores.getObject().openRange(descriptor.reference(),0,descriptor.size());
            }
        };
    }
    private static Metadata map(ResultSet r,int ignored) throws SQLException {
        return new Metadata(new PrivateAiSourceCurrentness.Expected(r.getObject("owner_user_id",UUID.class),r.getObject("note_id",UUID.class),
            r.getObject("attachment_id",UUID.class),r.getLong("revision"),r.getLong("ai_generation"),r.getObject("processing_generation",Long.class)),
            r.getString("modality"),r.getBoolean("ai_enabled"),r.getString("lifecycle_state"),r.getString("media_type"),r.getLong("size_bytes"),
            r.getObject("duration_seconds",Double.class),r.getObject("page_count",Integer.class));
    }
}
