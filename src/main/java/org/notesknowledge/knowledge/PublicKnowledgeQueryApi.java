package org.notesknowledge.knowledge;

import java.util.List;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current public surrogate retrieval. No private vector union, embedding or provider fallback. */
@Service
public class PublicKnowledgeQueryApi {
    public record Segment(String text,String heading,int start,int end) {
        @Override public String toString(){return "PublicSegment[REDACTED]";}
    }
    private final PublicKnowledgeSource sources;
    private final ObjectProvider<JdbcClient> clients;
    PublicKnowledgeQueryApi(PublicKnowledgeSource sources,ObjectProvider<JdbcClient> clients){this.sources=sources;this.clients=clients;}
    @Transactional(readOnly=true,timeout=3)
    public List<Segment> current(PublicKnowledgeSource.Expected expected) {
        if(!sources.current(expected,false))return List.of();
        var rows=clients.getObject().sql("""
            select s.text_content,s.heading,s.source_start,s.source_end from knowledge.public_derived_segment s
            join knowledge.public_derived_representation r on r.derived_representation_id=s.derived_representation_id
                and r.publication_id=s.publication_id and r.publication_generation=s.publication_generation
                and r.snapshot_revision=s.snapshot_revision and r.lineage_id=s.lineage_id
            where r.state='current' and r.publication_id=:id and r.publication_generation=:generation and r.snapshot_revision=:snapshot
                and r.lineage_id=:lineage order by s.segment_order limit 512
            """).param("id",expected.publication()).param("generation",expected.generation()).param("snapshot",expected.snapshot()).param("lineage",PublicKnowledgeRepository.LINEAGE)
            .query((r,i)->new Segment(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4))).list();
        return sources.current(expected,false)?rows:List.of();
    }
}
