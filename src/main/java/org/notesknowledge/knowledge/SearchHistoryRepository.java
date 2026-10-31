package org.notesknowledge.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SearchHistoryRepository {
    record Row(UUID id,UUID owner,UUID work,String state,String visibleState,Instant created,Instant expires,
            KnowledgeOperationMaterialCipher.Envelope question,KnowledgeOperationMaterialCipher.Envelope result) {
        @Override public String toString(){return "SearchHistoryRow[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    SearchHistoryRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    void lock(UUID owner){clients.getObject().sql("select pg_advisory_xact_lock(hashtextextended('knowledge.search-history:'||:owner,0))").param("owner",owner.toString()).query((r,i)->0).single();}
    List<Row> page(UUID owner,Instant before,UUID id,int limit){return clients.getObject().sql("""
        select h.history_id,h.owner_user_id,h.work_id,h.state,h.created_at,h.expires_at,h.question_ciphertext,h.question_nonce,h.question_key_version,
            null::bytea result_ciphertext,null::bytea result_nonce,null::text result_key_version
            ,coalesce(case when h.state='pending' then w.state end,h.state) visible_state
        from knowledge.search_history_entry h left join knowledge.knowledge_work_intent w on w.knowledge_work_intent_id=h.work_id and w.owner_user_id=h.owner_user_id
        where h.owner_user_id=:owner and h.expires_at>clock_timestamp()
        and (:id::uuid is null or (h.created_at,h.history_id)<(:before::timestamptz,:id::uuid))
        order by h.created_at desc,h.history_id desc limit :limit
        """).param("owner",owner).param("before",before==null?null:before.atOffset(java.time.ZoneOffset.UTC),java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("id",id,java.sql.Types.OTHER).param("limit",limit).query(SearchHistoryRepository::map).list();}
    private static final String DETAIL="select h.*,coalesce(case when h.state='pending' then w.state end,h.state) visible_state from knowledge.search_history_entry h left join knowledge.knowledge_work_intent w on w.knowledge_work_intent_id=h.work_id and w.owner_user_id=h.owner_user_id ";
    java.util.Optional<Row> owner(UUID owner,UUID id){return clients.getObject().sql(DETAIL+"where h.owner_user_id=:owner and h.history_id=:id and h.expires_at>clock_timestamp()")
        .param("owner",owner).param("id",id).query(SearchHistoryRepository::map).optional();}
    java.util.Optional<Row> work(UUID owner,UUID work){return clients.getObject().sql(DETAIL+"where h.owner_user_id=:owner and h.work_id=:work and h.expires_at>clock_timestamp()")
        .param("owner",owner).param("work",work).query(SearchHistoryRepository::map).optional();}
    void prune(UUID owner){
        clients.getObject().sql("update knowledge.search_history_entry set state='failed' where owner_user_id=:owner and state='pending' and work_id is null and created_at<clock_timestamp()-interval '2 minutes'").param("owner",owner).update();
        clients.getObject().sql("delete from knowledge.search_history_entry where owner_user_id=:owner and expires_at<=clock_timestamp()") .param("owner",owner).update();
        clients.getObject().sql("""
            delete from knowledge.search_history_entry where history_id in (
                select h.history_id from knowledge.search_history_entry h left join knowledge.knowledge_work_intent w
                    on w.knowledge_work_intent_id=h.work_id and w.owner_user_id=h.owner_user_id
                where h.owner_user_id=:owner and (h.state<>'pending' or w.state in ('completed','failed','obsolete','cancelled'))
                order by h.created_at desc,h.history_id desc offset greatest(0,49-(select count(*) from knowledge.search_history_entry a
                    left join knowledge.knowledge_work_intent aw on aw.knowledge_work_intent_id=a.work_id and aw.owner_user_id=a.owner_user_id
                    where a.owner_user_id=:owner and a.state='pending' and (a.work_id is null or aw.state in ('queued','claimed','retry_wait')))))
            """).param("owner",owner).update();
    }
    int count(UUID owner){return clients.getObject().sql("select count(*) from knowledge.search_history_entry where owner_user_id=:owner").param("owner",owner).query(Integer.class).single();}
    void insert(UUID id,UUID owner,UUID work,Instant created,Instant expires,KnowledgeOperationMaterialCipher.Envelope question){clients.getObject().sql("""
        insert into knowledge.search_history_entry(history_id,owner_user_id,work_id,question_ciphertext,question_nonce,question_key_version,created_at,expires_at)
        values(:id,:owner,:work,:cipher,:nonce,:key,:created,:expires)
        """).param("id",id).param("owner",owner).param("work",work,java.sql.Types.OTHER).param("cipher",question.ciphertext()).param("nonce",question.nonce()).param("key",question.keyVersion())
        .param("created",Timestamp.from(created)).param("expires",Timestamp.from(expires)).update();}
    boolean complete(UUID owner,UUID id,KnowledgeOperationMaterialCipher.Envelope result){return clients.getObject().sql("""
        update knowledge.search_history_entry set state='completed',result_ciphertext=:cipher,result_nonce=:nonce,result_key_version=:key
        where owner_user_id=:owner and history_id=:id and state='pending' and expires_at>clock_timestamp()
        """).param("owner",owner).param("id",id).param("cipher",result.ciphertext()).param("nonce",result.nonce()).param("key",result.keyVersion()).update()==1;}
    void unavailable(UUID owner,UUID id,String state){if(!List.of("failed","obsolete").contains(state))throw new IllegalArgumentException("Invalid history state");clients.getObject().sql("""
        update knowledge.search_history_entry set state=:state,result_ciphertext=null,result_nonce=null,result_key_version=null
        where owner_user_id=:owner and history_id=:id
        """).param("owner",owner).param("id",id).param("state",state).update();}
    void remove(UUID owner,UUID id){clients.getObject().sql("delete from knowledge.search_history_entry where owner_user_id=:owner and history_id=:id").param("owner",owner).param("id",id).update();}
    List<UUID> workIds(UUID owner){return clients.getObject().sql("select work_id from knowledge.search_history_entry where owner_user_id=:owner and work_id is not null limit 50").param("owner",owner).query(UUID.class).list();}
    void clear(UUID owner){clients.getObject().sql("delete from knowledge.search_history_entry where owner_user_id=:owner").param("owner",owner).update();}
    void sweep(){clients.getObject().sql("""
        with picked as(select history_id from knowledge.search_history_entry where expires_at<=clock_timestamp() order by expires_at limit 100 for update skip locked)
        delete from knowledge.search_history_entry h using picked where h.history_id=picked.history_id
        """).update();}
    private static Row map(java.sql.ResultSet r,int ignored)throws java.sql.SQLException {
        var q=new KnowledgeOperationMaterialCipher.Envelope(r.getBytes("question_ciphertext"),r.getBytes("question_nonce"),r.getString("question_key_version"));
        var result=r.getBytes("result_ciphertext");return new Row(r.getObject("history_id",UUID.class),r.getObject("owner_user_id",UUID.class),r.getObject("work_id",UUID.class),r.getString("state"),r.getString("visible_state"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),q,result==null?null:new KnowledgeOperationMaterialCipher.Envelope(result,r.getBytes("result_nonce"),r.getString("result_key_version")));
    }
}
