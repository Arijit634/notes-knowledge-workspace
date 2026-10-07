package org.notesknowledge.notes;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
class NotesPrivateQuerySourceAdapter implements PrivateQuerySource {
    private final ObjectProvider<JdbcClient> clients;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final AttachmentRepository attachments;
    private final ObjectProvider<PrivateAttachmentObjectStore> stores;
    NotesPrivateQuerySourceAdapter(ObjectProvider<JdbcClient> clients,ObjectProvider<AccountEligibilityApi> accounts,
        ObjectProvider<PlatformTransactionManager> managers,AttachmentRepository attachments,ObjectProvider<PrivateAttachmentObjectStore> stores) {
        this.clients=clients;this.accounts=accounts;this.managers=managers;this.attachments=attachments;this.stores=stores;
    }
    private <T>T transaction(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(managers.getObject());tx.setTimeout(3);return tx.execute(s->work.get());}
    private void owner(UUID owner){if(accounts.getIfAvailable()==null||!accounts.getObject().isEligible(owner))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);}
    private static final String SOURCES="""
        select n.owner_user_id,n.note_id,null::uuid attachment_id,n.revision,n.ai_generation,null::bigint attachment_generation,
            n.lifecycle_state,n.ai_enabled,'note' modality,n.created_at,n.title
        from notes.note n
        union all
        select n.owner_user_id,n.note_id,a.attachment_id,a.revision,n.ai_generation,a.processing_generation,
            n.lifecycle_state,n.ai_enabled,a.media_kind,a.created_at,n.title
        from notes.note n join notes.attachment a on a.note_id=n.note_id and a.owner_user_id=n.owner_user_id
        where a.storage_state='stored' and a.validation_state='accepted' and a.cleanup_state='retained' and a.removed_at is null
        """;
    public Boundary capture(UUID user,List<String> states){return transaction(()->{owner(user);return new Boundary(clients.getObject().sql("select clock_timestamp()").query(Timestamp.class).single().toInstant(),states);});}
    private JdbcClient.StatementSpec scoped(UUID user,Boundary b,boolean ai,String select) {
        owner(user);
        clients.getObject().sql("select set_config('statement_timeout','2000',true)").query(String.class).single();
        return clients.getObject().sql(select+" from ("+SOURCES+") s where owner_user_id=:owner and lifecycle_state in (:states) and created_at<=:start "+(ai?"and ai_enabled ":"and modality in ('note','pdf') "))
            .param("owner",user).param("states",b.lifecycles()).param("start",Timestamp.from(b.startedAt()));
    }
    public Page page(UUID user,Boundary b,Position after,int limit,boolean ai) {
        if(limit<1||limit>12)throw new IllegalArgumentException("Invalid query batch");
        return transaction(()->{
            scoped(user,b,ai,"select *");
            // Each family's creation cutoff is fixed at acceptance; cursor is only ordering, not authority.
            String suffix=" and (:after::uuid is null or (note_id,coalesce(attachment_id,'00000000-0000-0000-0000-000000000000'::uuid))>(:after::uuid,:attachment::uuid)) order by note_id,attachment_id nulls first limit :limit";
            // JdbcClient's SQL is immutable; build the same bounded predicates with the continuation.
            var rows=clients.getObject().sql("select * from ("+SOURCES+") s where owner_user_id=:owner and lifecycle_state in (:states) and created_at<=:start "+(ai?"and ai_enabled":"and modality in ('note','pdf')")+suffix)
                .param("owner",user).param("states",b.lifecycles()).param("start",Timestamp.from(b.startedAt()))
                .param("after",after==null?null:after.noteId(),java.sql.Types.OTHER).param("attachment",after==null||after.attachmentId()==null?new UUID(0,0):after.attachmentId())
                .param("limit",limit).query(NotesPrivateQuerySourceAdapter::map).list();
            var last=rows.isEmpty()?null:rows.getLast().expected();
            return new Page(rows,rows.size()<limit?null:new Position(last.noteId(),last.attachmentId()));
        });
    }
    public Source note(UUID user,UUID note){return transaction(()->{owner(user);return clients.getObject().sql("select * from ("+SOURCES+") s where owner_user_id=:owner and note_id=:note and attachment_id is null and lifecycle_state in ('active','archived')")
        .param("owner",user).param("note",note).query(NotesPrivateQuerySourceAdapter::map).optional().orElseThrow(()->ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));});}
    public Source source(UUID user,UUID note,UUID attachment){return transaction(()->{owner(user);return clients.getObject().sql("select * from ("+SOURCES+") s where owner_user_id=:owner and note_id=:note and attachment_id is not distinct from :attachment and lifecycle_state in ('active','archived')")
        .param("owner",user).param("note",note).param("attachment",attachment,java.sql.Types.OTHER).query(NotesPrivateQuerySourceAdapter::map).optional().orElseThrow(()->ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));});}
    public boolean matches(Source source,boolean ai){return transaction(()->{
        var e=source.expected();owner(e.owner());
        return clients.getObject().sql("select count(*) from ("+SOURCES+") s where owner_user_id=:owner and note_id=:note and attachment_id is not distinct from :attachment "
            +"and revision=:revision and ai_generation=:generation and attachment_generation is not distinct from :attachmentGeneration and lifecycle_state=:state "+(ai?"and ai_enabled":""))
            .param("owner",e.owner()).param("note",e.noteId()).param("attachment",e.attachmentId(),java.sql.Types.OTHER).param("revision",e.revision()).param("generation",e.aiGeneration())
            .param("attachmentGeneration",e.attachmentGeneration(),java.sql.Types.BIGINT).param("state",source.lifecycle()).query(Integer.class).single()==1;
    });}
    public String fingerprint(UUID user,Boundary b,boolean ai){return transaction(()->{
        owner(user);
        String hash="encode(sha256(convert_to(note_id::text||'|'||coalesce(attachment_id::text,'-')||'|'||revision||'|'||ai_generation||'|'||coalesce(attachment_generation::text,'-')||'|'||lifecycle_state||'|'||ai_enabled,'UTF8')),'hex')";
        String words=java.util.stream.IntStream.range(0,4).mapToObj(i->"coalesce(bit_xor(('x'||substr(h,"+(1+i*16)+",16))::bit(64)::bigint),0)::text").collect(java.util.stream.Collectors.joining("||':'||"));
        return clients.getObject().sql("select count(*)::text||':'||"+words+" from (select "+hash+" h from ("+SOURCES+") s where owner_user_id=:owner and lifecycle_state in (:states) and created_at<=:start "+(ai?"and ai_enabled ":"and modality in ('note','pdf') ")+"order by note_id,attachment_id nulls first limit 10001) bounded")
            .param("owner",user).param("states",b.lifecycles()).param("start",Timestamp.from(b.startedAt())).query(String.class).single();
    });}
    public boolean validate(List<Source> expected,boolean ai) {
        if(expected.isEmpty())return true;
        if(expected.size()>1000)return false;
        UUID user=expected.getFirst().expected().owner();
        if(expected.stream().anyMatch(s->!user.equals(s.expected().owner())))return false;
        return transaction(()->{
            owner(user);var notes=expected.stream().map(s->s.expected().noteId()).distinct().toList();
            // Lock current owner-module rows in stable order in this short validation transaction.
            clients.getObject().sql("select note_id from notes.note where owner_user_id=:owner and note_id in (:notes) order by note_id for share")
                .param("owner",user).param("notes",notes).query(UUID.class).list();
            var media=expected.stream().map(s->s.expected().attachmentId()).filter(java.util.Objects::nonNull).distinct().toList();
            if(!media.isEmpty())clients.getObject().sql("select attachment_id from notes.attachment where owner_user_id=:owner and attachment_id in (:media) order by attachment_id for share")
                .param("owner",user).param("media",media).query(UUID.class).list();
            var rows=clients.getObject().sql("select * from ("+SOURCES+") s where owner_user_id=:owner and note_id in (:notes) and lifecycle_state in ('active','archived') "+(ai?"and ai_enabled ":"")+"order by note_id,attachment_id nulls first limit 21001")
                .param("owner",user).param("notes",notes).query(NotesPrivateQuerySourceAdapter::map).list();
            if(rows.size()>21000)return false;
            var current=rows.stream().collect(java.util.stream.Collectors.toMap(s->new Position(s.expected().noteId(),s.expected().attachmentId()),java.util.function.Function.identity()));
            return expected.stream().allMatch(s->{var row=current.get(new Position(s.expected().noteId(),s.expected().attachmentId()));return row!=null&&row.expected().equals(s.expected())&&row.lifecycle().equals(s.lifecycle());});
        });
    }
    public List<Text> deterministicText(Source source) {
        if(!matches(source,false))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        var e=source.expected();
        if(e.attachmentId()==null)return transaction(()->{if(!matches(source,false))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
            return List.of(new Text(clients.getObject().sql("select title||E'\\n'||markdown from notes.note where owner_user_id=:owner and note_id=:note")
                .param("owner",e.owner()).param("note",e.noteId()).query(String.class).single(),null));});
        if(!"pdf".equals(source.modality()))return List.of();
        var descriptor=transaction(()->{if(!matches(source,false))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);return attachments.content(e.owner(),e.noteId(),e.attachmentId()).orElseThrow();});
        if(descriptor.size()>20*1024*1024)throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Byte IO must be outside transaction");
        try(var stream=stores.getObject().openRange(descriptor.reference(),0,descriptor.size())) {
            byte[] bytes=stream.readNBytes((int)descriptor.size()+1);if(bytes.length!=descriptor.size())throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
            var result=PrivatePdfTextExtractor.extract(bytes).stream().map(p->new Text(p.text(),p.page())).toList();
            if(!matches(source,false))throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);return result;
        } catch(java.io.IOException failure){throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    }
    private static Source map(java.sql.ResultSet r,int ignored)throws java.sql.SQLException {
        return new Source(new PrivateAiSourceCurrentness.Expected(r.getObject("owner_user_id",UUID.class),r.getObject("note_id",UUID.class),r.getObject("attachment_id",UUID.class),r.getLong("revision"),r.getLong("ai_generation"),r.getObject("attachment_generation",Long.class)),
            r.getString("lifecycle_state"),r.getBoolean("ai_enabled"),r.getString("modality"),r.getTimestamp("created_at").toInstant(),r.getString("title"));
    }
}
