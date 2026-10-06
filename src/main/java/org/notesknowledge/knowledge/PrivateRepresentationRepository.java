package org.notesknowledge.knowledge;

import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PrivateRepresentationRepository {
    private final ObjectProvider<JdbcClient> clients;
    PrivateRepresentationRepository(ObjectProvider<JdbcClient> clients) { this.clients=clients; }
    void invalidate(UUID owner,UUID note,UUID attachment) {
        String filter="owner_user_id=:owner and source_note_id=:note"+(attachment==null?"":" and source_attachment_id=:attachment");
        var roots=clients.getObject().sql("update knowledge.private_derived_representation set state='obsolete',obsolete_at=clock_timestamp() where state='ready' and "+filter)
            .param("owner",owner).param("note",note);
        if(attachment!=null)roots.param("attachment",attachment);roots.update();
        var work=clients.getObject().sql("update knowledge.knowledge_work_intent set state='obsolete',next_attempt_at=null,lease_owner=null,lease_token=null,lease_until=null,"
            +"failure_code='invalid_source',updated_at=clock_timestamp() where state in ('queued','claimed','retry_wait') and "+filter)
            .param("owner",owner).param("note",note);
        if(attachment!=null)work.param("attachment",attachment);work.update();
    }
    boolean ready(PrivateAiSourceCurrentness.Expected e,EmbeddingLineage lineage) {
        return sourceStatement("select count(*) from knowledge.private_derived_representation where state='ready' and ",e,lineage.id())
            .param("policy",lineage.policyId()).query(Integer.class).single()>0;
    }
    String workState(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e,String lineage) {
        return clients.getObject().sql("select case when state='claimed' and lease_until<=clock_timestamp() then 'retry_wait' else state end from knowledge.knowledge_work_intent where owner_user_id=:owner and dedupe_key=:dedupe order by created_at desc,knowledge_work_intent_id desc limit 1")
            .param("owner",e.owner()).param("dedupe",KnowledgeWorkRepository.dedupe(kind,e,lineage)).query(String.class).optional().orElse(null);
    }
    boolean oldRoot(PrivateAiSourceCurrentness.Expected e) {
        return clients.getObject().sql("select exists(select 1 from knowledge.private_derived_representation where owner_user_id=:owner and source_note_id=:note and source_attachment_id is not distinct from :attachment)")
            .param("owner",e.owner()).param("note",e.noteId()).param("attachment",e.attachmentId(),Types.OTHER).query(Boolean.class).single();
    }
    private JdbcClient.StatementSpec sourceStatement(String prefix,PrivateAiSourceCurrentness.Expected e,String lineage) {
        return clients.getObject().sql(prefix+"owner_user_id=:owner and source_note_id=:note and source_attachment_id is not distinct from :attachment "
            +"and source_revision=:revision and processing_generation=:generation and attachment_generation is not distinct from :attachmentGeneration "
            +"and lineage_id=:lineage and processing_policy_id=:policy")
            .param("owner",e.owner()).param("note",e.noteId()).param("attachment",e.attachmentId(),Types.OTHER)
            .param("revision",e.revision()).param("generation",e.aiGeneration()).param("attachmentGeneration",e.attachmentGeneration(),Types.BIGINT)
            .param("lineage",lineage);
    }
    void activate(PrivateAiSourceCurrentness.Expected e,EmbeddingLineage l,List<DerivedSegment> segments,List<float[]> vectors) {
        if(segments.isEmpty()||segments.size()>512||segments.size()!=vectors.size())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        for(float[] vector:vectors)l.validate(vector);
        // Source locks acquired by the gate serialize this switch with owner mutations.
        clients.getObject().sql("update knowledge.private_derived_representation set state='obsolete',obsolete_at=clock_timestamp() "
            +"where owner_user_id=:owner and source_note_id=:note and source_attachment_id is not distinct from :attachment and state='ready'")
            .param("owner",e.owner()).param("note",e.noteId()).param("attachment",e.attachmentId(),Types.OTHER).update();
        UUID parent=clients.getObject().sql("""
            insert into knowledge.private_derived_representation(owner_user_id,source_kind,source_note_id,source_attachment_id,
                source_revision,processing_generation,attachment_generation,derivation_class,processing_policy_id,lineage_id,
                lineage_configuration,modality,embedding_dimension,distance_operator)
            values(:owner,:kind,:note,:attachment,:revision,:generation,:attachmentGeneration,'text_surrogate',:policy,:lineage,:configuration,:modality,:dimension,:operator)
            returning derived_representation_id
            """).param("owner",e.owner()).param("kind",e.attachmentId()==null?"note":"attachment").param("note",e.noteId())
            .param("attachment",e.attachmentId(),Types.OTHER).param("revision",e.revision()).param("generation",e.aiGeneration())
            .param("attachmentGeneration",e.attachmentGeneration(),Types.BIGINT).param("policy",l.policyId()).param("lineage",l.id())
            .param("configuration",l.configuration()).param("modality",l.modality()).param("dimension",l.dimension()).param("operator",l.operator()).query(UUID.class).single();
        for(int i=0;i<segments.size();i++) {
            var s=segments.get(i);
            clients.getObject().sql("""
                insert into knowledge.private_derived_segment(parent_id,owner_user_id,ordinal,surrogate_text,segment_kind,heading_ancestry,
                    source_start,source_end,page_number,time_start,time_end,region_x,region_y,region_width,region_height,lineage_id,embedding_dimension,embedding)
                values(:parent,:owner,:ordinal,:text,:kind,:heading,:start,:end,:page,:timeStart,:timeEnd,:x,:y,:width,:height,:lineage,:dimension,:vector::vector)
                """).param("parent",parent).param("owner",e.owner()).param("ordinal",i).param("text",s.text()).param("kind",s.kind()).param("heading",s.heading())
                .param("start",s.start(),Types.INTEGER).param("end",s.end(),Types.INTEGER).param("page",s.page(),Types.INTEGER)
                .param("timeStart",s.timeStart(),Types.DOUBLE).param("timeEnd",s.timeEnd(),Types.DOUBLE)
                .param("x",s.x(),Types.DOUBLE).param("y",s.y(),Types.DOUBLE).param("width",s.width(),Types.DOUBLE).param("height",s.height(),Types.DOUBLE)
                .param("lineage",l.id()).param("dimension",l.dimension()).param("vector",vectorLiteral(vectors.get(i))).update();
        }
    }
    static String vectorLiteral(float[] vector) {
        var out=new StringBuilder("[");for(int i=0;i<vector.length;i++){if(i>0)out.append(',');out.append(Float.toString(vector[i]));}return out.append(']').toString();
    }
}
