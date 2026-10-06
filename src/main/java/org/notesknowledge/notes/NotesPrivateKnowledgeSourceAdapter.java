package org.notesknowledge.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.knowledge.spi.PrivateKnowledgeSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Root-package adapter keeps Notes repositories private. Each call joins one SHORT caller transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
class NotesPrivateKnowledgeSourceAdapter implements PrivateKnowledgeSource {
    private final ObjectProvider<JdbcClient> clients;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    NotesPrivateKnowledgeSourceAdapter(ObjectProvider<JdbcClient> clients,ObjectProvider<AccountEligibilityApi> accounts) {
        this.clients=clients;this.accounts=accounts;
    }
    UUID owner() {
        UUID owner=NotesActor.owner();
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes browser)
                || accounts.getIfAvailable()==null) throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        accounts.getObject().requireCurrentOwner(owner,browser.getRequest());
        return owner;
    }
    private static String binding(UUID owner) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public Boundary capture(Scope scope) {
        UUID owner=owner();
        Instant start=clients.getObject().sql("select clock_timestamp()").query(Timestamp.class).single().toInstant();
        // The family cutoff is time, NOT the greatest row visible in this transaction.
        // A pre-cutoff insert can still be uncommitted here and must enter later validation.
        // PostgreSQL orders UUIDs unsigned; this sentinel includes every ID at the cutoff.
        return new Boundary(start,start,new UUID(-1L,-1L),binding(owner),scope);
    }
    private org.springframework.jdbc.core.SqlParameterValue states(Scope scope) {
        return new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.ARRAY,
                scope.lifecycles().stream().map(s->s.name().toLowerCase(java.util.Locale.ROOT)).sorted().toArray(String[]::new));
    }
    private JdbcClient.StatementSpec scoped(Boundary boundary,String columns,UUID after,int limit) {
        UUID owner=owner();
        if(!binding(owner).equals(boundary.actorBinding())) throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        // Server bound query work as well as result/memory. Restored automatically at transaction end.
        clients.getObject().sql("select set_config('statement_timeout','2000',true)").query(String.class).single();
        return clients.getObject().sql("select "+columns+" from notes.note where owner_user_id=:owner "
                +"and lifecycle_state=any(cast(:states as text[])) and created_at<=:start "
                +"and (created_at,note_id)<=(:upperTime,:upperId) "
                +(after==null?"":"and note_id>:after ")+"order by note_id limit :limit")
                .param("owner",owner).param("states",states(boundary.scope())).param("start",Timestamp.from(boundary.startedAt()))
                .param("upperTime",Timestamp.from(boundary.upperCreatedAt())).param("upperId",boundary.upperId())
                .param("limit",limit).params(after==null?java.util.Map.of():java.util.Map.of("after",after));
    }
    private static Metadata metadata(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Metadata(r.getObject("note_id",UUID.class),r.getLong("revision"),
                Lifecycle.valueOf(r.getString("lifecycle_state").toUpperCase(java.util.Locale.ROOT)),r.getLong("ai_generation"));
    }
    public BodyPage readCurrent(Boundary b,UUID after,Purpose purpose) {
        if(purpose!=Purpose.DETERMINISTIC_URL_EXTRACTION) throw new IllegalArgumentException("Unsupported source purpose");
        var rows=scoped(b,"note_id,revision,lifecycle_state,ai_generation,title,markdown",after,BODY_BATCH)
                .query((r,i)->new CurrentNote(metadata(r),r.getString("title"),r.getString("markdown"))).list();
        return new BodyPage(rows,rows.size()==BODY_BATCH?rows.getLast().metadata().noteId():null);
    }
    public MetadataPage readMetadata(Boundary b,UUID after) {
        var rows=scoped(b,"note_id,revision,lifecycle_state,ai_generation",after,METADATA_BATCH).query((r,i)->metadata(r)).list();
        return new MetadataPage(rows,rows.size()==METADATA_BATCH?rows.getLast().noteId():null);
    }
    public Fingerprint currentFingerprint(Boundary b) {
        // Single statement snapshot, scalar output and at most MAX_SOURCES+1 metadata rows.
        // XOR of per-row SHA256 plus count is shared with the streaming reducer, never authorization.
        UUID owner=owner();
        if(!binding(owner).equals(b.actorBinding())) throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        clients.getObject().sql("select set_config('statement_timeout','2000',true)").query(String.class).single();
        String rowHash="encode(sha256(convert_to(note_id::text||'|'||revision::text||'|'||lifecycle_state||'|'||ai_generation::text,'UTF8')),'hex')";
        String words=java.util.stream.IntStream.range(0,4).mapToObj(i->
                "coalesce(bit_xor(('x'||substr(h,"+(1+i*16)+",16))::bit(64)::bigint),0) as w"+i).collect(java.util.stream.Collectors.joining(","));
        return clients.getObject().sql("select count(*) as total,"+words+" from (select "+rowHash+" as h from notes.note "
                +"where owner_user_id=:owner and lifecycle_state=any(cast(:states as text[])) and created_at<=:start "
                +"and (created_at,note_id)<=(:upperTime,:upperId) order by note_id limit :limit) bounded")
                .param("owner",owner).param("states",states(b.scope())).param("start",Timestamp.from(b.startedAt()))
                .param("upperTime",Timestamp.from(b.upperCreatedAt())).param("upperId",b.upperId()).param("limit",MAX_SOURCES+1)
                .query((r,i)->new Fingerprint(r.getLong("total"),List.of(r.getLong("w0"),r.getLong("w1"),r.getLong("w2"),r.getLong("w3")))).single();
    }
}
