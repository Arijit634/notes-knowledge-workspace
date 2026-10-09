package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Owner/lineage selection precedes text exposure; current source validation belongs to the caller's short transaction. */
@Repository
class QueryEvidenceRepository {
    record Evidence(UUID segmentId,UUID representationId,PrivateAiSourceCurrentness.Expected expected,String lineage,
            String modality,int ordinal,DerivedSegment segment) {
        @Override public String toString(){return "QueryEvidence[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    private final PrivateAiSourceCurrentness current;
    QueryEvidenceRepository(ObjectProvider<JdbcClient> clients,PrivateAiSourceCurrentness current){this.clients=clients;this.current=current;}
    List<Evidence> source(PrivateAiSourceCurrentness.Expected source,EmbeddingLineage lineage) {
        if(!current.matches(source))return List.of();
        return source(source,lineage,null,0);
    }
    List<Evidence> page(PrivateAiSourceCurrentness.Expected source,EmbeddingLineage lineage,int nextOrdinal) {
        if(nextOrdinal<0||nextOrdinal>512)throw new IllegalArgumentException("Invalid segment continuation");
        return source(source,lineage,null,nextOrdinal);
    }
    Evidence candidate(ExactPrivateVectorSearch.Candidate candidate,EmbeddingLineage lineage) {
        return source(candidate.expected(),lineage,candidate.segmentId(),0).stream()
            .filter(e->e.ordinal()==candidate.ordinal()).findFirst().orElse(null);
    }
    private List<Evidence> source(PrivateAiSourceCurrentness.Expected source,EmbeddingLineage lineage,UUID segmentId,int nextOrdinal) {
        if(!current.matches(source))return List.of();
        return clients.getObject().sql("""
            select s.*,r.source_note_id,r.source_attachment_id,r.source_revision,r.processing_generation,r.attachment_generation,r.modality
            from knowledge.private_derived_representation r join knowledge.private_derived_segment s
                on r.derived_representation_id=s.parent_id and r.owner_user_id=s.owner_user_id
            where r.owner_user_id=:owner and r.source_note_id=:note and r.source_attachment_id is not distinct from :attachment
                and r.source_revision=:revision and r.processing_generation=:generation
                and r.attachment_generation is not distinct from :attachmentGeneration and r.state='ready'
                and r.lineage_id=:lineage and r.processing_policy_id=:policy and r.embedding_dimension=:dimension
                and s.lineage_id=:lineage and s.owner_user_id=:owner
                and (:segment::uuid is null or s.derived_segment_id=:segment)
                and s.ordinal>=:nextOrdinal
            order by s.ordinal limit 13
            """).param("owner",source.owner()).param("note",source.noteId()).param("attachment",source.attachmentId(),java.sql.Types.OTHER)
            .param("revision",source.revision()).param("generation",source.aiGeneration()).param("attachmentGeneration",source.attachmentGeneration(),java.sql.Types.BIGINT)
            .param("lineage",lineage.id()).param("policy",lineage.policyId()).param("dimension",lineage.dimension())
            .param("segment",segmentId,java.sql.Types.OTHER)
            .param("nextOrdinal",nextOrdinal)
            .query((r,i)->new Evidence(r.getObject("derived_segment_id",UUID.class),r.getObject("parent_id",UUID.class),source,lineage.id(),r.getString("modality"),r.getInt("ordinal"),
                new DerivedSegment(r.getString("surrogate_text"),r.getString("segment_kind"),r.getString("heading_ancestry"),r.getObject("source_start",Integer.class),r.getObject("source_end",Integer.class),
                    r.getObject("page_number",Integer.class),r.getObject("time_start",Double.class),r.getObject("time_end",Double.class),r.getObject("region_x",Double.class),r.getObject("region_y",Double.class),
                    r.getObject("region_width",Double.class),r.getObject("region_height",Double.class)))).list();
    }
    boolean current(Evidence e,EmbeddingLineage lineage) {
        return e.lineage().equals(lineage.id())&&current.matches(e.expected())&&clients.getObject().sql("""
            select count(*) from knowledge.private_derived_representation r join knowledge.private_derived_segment s
            on r.derived_representation_id=s.parent_id and r.owner_user_id=s.owner_user_id
            where r.owner_user_id=:owner and r.derived_representation_id=:root and s.derived_segment_id=:segment
                and r.state='ready' and r.lineage_id=:lineage and r.processing_policy_id=:policy and r.embedding_dimension=:dimension
            """).param("owner",e.expected().owner()).param("root",e.representationId()).param("segment",e.segmentId())
            .param("lineage",lineage.id()).param("policy",lineage.policyId()).param("dimension",lineage.dimension()).query(Integer.class).single()==1;
    }
    float[] vector(Evidence e) {
        String text=clients.getObject().sql("select embedding::text from knowledge.private_derived_segment where owner_user_id=:owner and derived_segment_id=:id")
            .param("owner",e.expected().owner()).param("id",e.segmentId()).query(String.class).single();
        String[] fields=text.substring(1,text.length()-1).split(",");float[] v=new float[fields.length];for(int i=0;i<v.length;i++)v[i]=Float.parseFloat(fields[i]);return v;
    }
}
