package org.notesknowledge.publishing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.notesknowledge.discovery.spi.PublicDiscoveryScope;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.notesknowledge.profile.PublicProfileApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.MANDATORY)
class PublishingPublicSourceAdapter implements PublicDiscoveryScope,PublicKnowledgeSource {
    private final ObjectProvider<JdbcClient> clients;
    private final PublicProfileApi profiles;
    PublishingPublicSourceAdapter(ObjectProvider<JdbcClient> clients,PublicProfileApi profiles){this.clients=clients;this.profiles=profiles;}
    @Override public List<Current> current(List<UUID> ids,boolean lock) {
        if(ids.isEmpty())return List.of();
        if(ids.size()>100)throw new IllegalArgumentException("Public scope batch exceeds bound");
        record Root(UUID id,long generation,long snapshot,UUID author) { }
        var roots=clients.getObject().sql("select publication_id,publication_generation,snapshot_revision,public_profile_projection_id from publishing.publication where publication_id in (:ids) and availability='active' order by publication_id"+(lock?" for share":""))
            .param("ids",ids).query((r,i)->new Root(r.getObject(1,UUID.class),r.getLong(2),r.getLong(3),r.getObject(4,UUID.class))).list();
        var authors=profiles.resolveBatch(roots.stream().map(Root::author).distinct().toList());
        return roots.stream().filter(r->authors.containsKey(r.author())).map(r->new Current(r.id(),r.generation(),r.snapshot(),authors.get(r.author()))).toList();
    }
    @Override public Optional<Source> resolve(Expected expected,boolean lock) {
        if(!current(expected,lock))return Optional.empty();
        return clients.getObject().sql("select markdown from publishing.publication where publication_id=:id and publication_generation=:generation and snapshot_revision=:snapshot and availability='active'")
            .param("id",expected.publication()).param("generation",expected.generation()).param("snapshot",expected.snapshot())
            .query(String.class).optional().map(text->new Source(expected,text));
    }
    @Override public boolean current(Expected expected,boolean lock) {
        return current(List.of(expected.publication()),lock).stream().anyMatch(c->c.generation()==expected.generation()&&c.snapshot()==expected.snapshot());
    }
    @Override public Inventory inventory(UUID after,int limit) {
        if(limit<1||limit>25)throw new IllegalArgumentException("Public reconciliation batch exceeds bound");
        var q=clients.getObject().sql("select publication_id,publication_generation,snapshot_revision from publishing.publication where availability='active'"+(after==null?"":" and publication_id>:after")+" order by publication_id limit :limit").param("limit",limit);
        if(after!=null)q=q.param("after",after);
        var roots=q.query((r,i)->new Expected(r.getObject(1,UUID.class),r.getLong(2),r.getLong(3))).list();
        var current=current(roots.stream().map(Expected::publication).toList(),false);
        var eligible=roots.stream().filter(e->current.stream().anyMatch(c->c.id().equals(e.publication())&&c.generation()==e.generation()&&c.snapshot()==e.snapshot())).toList();
        return new Inventory(eligible,roots.size()==limit?roots.getLast().publication():null);
    }
}
