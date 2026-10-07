package org.notesknowledge.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.LeaseToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** All mutations run in short caller transactions. A lease is opaque, not source authority. */
@Repository
class KnowledgeOperationRepository {
    record Row(UUID id,UUID owner,String purpose,String state,Instant created,Instant expires,long version,
            KnowledgeOperationMaterialCipher.Envelope input,KnowledgeOperationMaterialCipher.Envelope result,String metadata,
            LeaseToken lease) {
        @Override public String toString(){return "KnowledgeOperationRow[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    KnowledgeOperationRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    void insert(UUID id,UUID owner,String purpose,Instant created,Instant expiry,String metadata,KnowledgeOperationMaterialCipher.Envelope input) {
        clients.getObject().sql("""
            insert into knowledge.knowledge_work_intent(knowledge_work_intent_id,owner_user_id,work_class,source_kind,
                derivation_class,target_lineage_id,max_attempts,next_attempt_at,dedupe_key,operation_purpose,operation_metadata,
                operation_expires_at,input_ciphertext,input_nonce,input_key_version,created_at,updated_at)
            values(:id,:owner,'private_knowledge_operation',null,null,null,3,clock_timestamp(),
                encode(sha256(convert_to(:id::text,'UTF8')),'hex'),:purpose,:metadata::jsonb,:expiry,:cipher,:nonce,:key,:created,:created)
            """).param("id",id).param("owner",owner).param("purpose",purpose).param("metadata",metadata).param("expiry",Timestamp.from(expiry))
            .param("cipher",input.ciphertext()).param("nonce",input.nonce()).param("key",input.keyVersion()).param("created",Timestamp.from(created)).update();
    }
    java.util.Optional<Row> owner(UUID owner,UUID id) {
        return clients.getObject().sql("select * from knowledge.knowledge_work_intent where knowledge_work_intent_id=:id and owner_user_id=:owner and work_class='private_knowledge_operation' and operation_expires_at>clock_timestamp()")
            .param("id",id).param("owner",owner).query(KnowledgeOperationRepository::map).optional();
    }
    List<Row> claim(int limit) {
        if(limit<1||limit>2)throw new IllegalArgumentException("Invalid claim budget");
        return clients.getObject().sql("""
            with picked as (select knowledge_work_intent_id from knowledge.knowledge_work_intent
                where work_class='private_knowledge_operation' and operation_expires_at>clock_timestamp() and attempt_count<max_attempts
                    and ((state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp()) or (state='claimed' and lease_until<=clock_timestamp()))
                order by created_at,knowledge_work_intent_id limit :limit for update skip locked)
            update knowledge.knowledge_work_intent w set state='claimed',attempt_count=attempt_count+1,next_attempt_at=null,
                lease_owner='knowledge-query',lease_token=uuidv7(),lease_until=clock_timestamp()+interval '120 seconds',updated_at=clock_timestamp()
                from picked where w.knowledge_work_intent_id=picked.knowledge_work_intent_id returning w.*
            """).param("limit",limit).query(KnowledgeOperationRepository::map).list();
    }
    private JdbcClient.StatementSpec fenced(Row row,String prefix) {
        return clients.getObject().sql(prefix+" knowledge_work_intent_id=:id and owner_user_id=:owner and work_class='private_knowledge_operation' and operation_purpose=:purpose and state='claimed' and lease_token=:token and lease_owner='knowledge-query' and lease_until>clock_timestamp() and operation_expires_at>clock_timestamp() and checkpoint_version=:version")
            .param("id",row.id()).param("owner",row.owner()).param("purpose",row.purpose()).param("token",row.lease().value()).param("version",row.version());
    }
    boolean current(Row row){return fenced(row,"select count(*) from knowledge.knowledge_work_intent where").query(Integer.class).single()==1;}
    boolean checkpoint(Row row,String metadata,KnowledgeOperationMaterialCipher.Envelope result) {
        return fenced(row,"update knowledge.knowledge_work_intent set operation_metadata=:metadata::jsonb,result_ciphertext=:cipher,result_nonce=:nonce,result_key_version=:key,checkpoint_version=checkpoint_version+1,lease_until=clock_timestamp()+interval '120 seconds',updated_at=clock_timestamp() where")
            .param("metadata",metadata).param("cipher",result.ciphertext()).param("nonce",result.nonce()).param("key",result.keyVersion()).update()==1;
    }
    boolean complete(Row row,String metadata,KnowledgeOperationMaterialCipher.Envelope result) {
        return fenced(row,"update knowledge.knowledge_work_intent set state='completed',operation_metadata=:metadata::jsonb,result_ciphertext=:cipher,result_nonce=:nonce,result_key_version=:key,checkpoint_version=checkpoint_version+1,input_ciphertext=null,input_nonce=null,input_key_version=null,lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where")
            .param("metadata",metadata).param("cipher",result.ciphertext()).param("nonce",result.nonce()).param("key",result.keyVersion()).update()==1;
    }
    boolean terminate(Row row,String state) {
        if(!java.util.Set.of("failed","obsolete").contains(state))throw new IllegalArgumentException("Invalid worker terminal state");
        return fenced(row,"update knowledge.knowledge_work_intent set state=:state,input_ciphertext=null,input_nonce=null,input_key_version=null,result_ciphertext=null,result_nonce=null,result_key_version=null,lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where")
            .param("state",state).update()==1;
    }
    void cancel(UUID owner,UUID id){clients.getObject().sql("""
        update knowledge.knowledge_work_intent set state='cancelled',input_ciphertext=null,input_nonce=null,input_key_version=null,
            result_ciphertext=null,result_nonce=null,result_key_version=null,lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=clock_timestamp()
        where owner_user_id=:owner and knowledge_work_intent_id=:id and work_class='private_knowledge_operation' and state in ('queued','retry_wait','claimed')
        """).param("owner",owner).param("id",id).update();}
    void obsolete(UUID owner,UUID id){clients.getObject().sql("""
        update knowledge.knowledge_work_intent set state='obsolete',result_ciphertext=null,result_nonce=null,result_key_version=null,updated_at=clock_timestamp()
        where owner_user_id=:owner and knowledge_work_intent_id=:id and work_class='private_knowledge_operation' and state='completed'
        """).param("owner",owner).param("id",id).update();}
    void expire(){clients.getObject().sql("""
        with picked as (select knowledge_work_intent_id from knowledge.knowledge_work_intent
            where work_class='private_knowledge_operation' and ((operation_expires_at<=clock_timestamp() and (input_ciphertext is not null or result_ciphertext is not null))
                or (state='claimed' and lease_until<=clock_timestamp() and attempt_count>=max_attempts))
            order by operation_expires_at limit 100 for update skip locked)
        update knowledge.knowledge_work_intent w set state=case when w.state in ('queued','claimed','retry_wait') then 'obsolete' else w.state end,
            input_ciphertext=null,input_nonce=null,input_key_version=null,result_ciphertext=null,result_nonce=null,result_key_version=null,
            lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=clock_timestamp()
            from picked where w.knowledge_work_intent_id=picked.knowledge_work_intent_id
        """).update();}
    private static Row map(java.sql.ResultSet r,int ignored)throws java.sql.SQLException {
        return new Row(r.getObject("knowledge_work_intent_id",UUID.class),r.getObject("owner_user_id",UUID.class),r.getString("operation_purpose"),r.getString("state"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("operation_expires_at").toInstant(),r.getLong("checkpoint_version"),frame(r,"input"),frame(r,"result"),r.getString("operation_metadata"),
            r.getObject("lease_token",UUID.class)==null?null:LeaseToken.fromDatabase(r.getObject("lease_token",UUID.class)));
    }
    private static KnowledgeOperationMaterialCipher.Envelope frame(java.sql.ResultSet r,String prefix)throws java.sql.SQLException {
        return r.getBytes(prefix+"_ciphertext")==null?null:new KnowledgeOperationMaterialCipher.Envelope(r.getBytes(prefix+"_ciphertext"),r.getBytes(prefix+"_nonce"),r.getString(prefix+"_key_version"));
    }
}
