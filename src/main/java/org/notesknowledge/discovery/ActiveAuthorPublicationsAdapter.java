package org.notesknowledge.discovery;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.UUID;
import org.notesknowledge.profile.PublicProfileApi;
import org.notesknowledge.profile.spi.ActiveAuthorPublications;
import org.notesknowledge.websupport.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ActiveAuthorPublicationsAdapter implements ActiveAuthorPublications {
    private final ObjectProvider<JdbcClient> clients;
    private final PublicProfileApi profiles;
    private final OpaqueCursorCodec cursors;
    ActiveAuthorPublicationsAdapter(ObjectProvider<JdbcClient> clients,PublicProfileApi profiles,OpaqueCursorCodec cursors) {
        this.clients=clients;this.profiles=profiles;this.cursors=cursors;
    }
    @Override @Transactional(readOnly=true)
    public CursorPage<Item> page(UUID author,long generation,Integer requested,String cursor) {
        if(profiles.resolveById(author).generation()!=generation)throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        int limit=new PageLimitPolicy(20,100).resolve(requested);
        var paging=new ScopedCursorPage(cursors,"PUBLIC_AUTHOR",author+":"+generation,"ACTIVE","PUBLISHED_DESC");
        var position=paging.position(cursor);
        var query=clients.getObject().sql("""
            select publication_id,title,tags,published_at,updated_at from discovery.publication_projection
            where public_profile_projection_id=:author and active
            """+(position.before()==null?"":" and (published_at,publication_id) < (:before,:beforeId)")
            +" order by published_at desc,publication_id desc limit :limit").param("author",author).param("limit",limit+1);
        if(position.before()!=null)query=query.param("before",Timestamp.from(position.before())).param("beforeId",position.id());
        var rows=query.query((r,i)->new Item(r.getObject(1,UUID.class),r.getString(2),Arrays.asList((String[])r.getArray(3).getArray()),
            r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant())).list();
        if(profiles.resolveById(author).generation()!=generation)throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        boolean more=rows.size()>limit;var visible=more?rows.subList(0,limit):rows;
        return new CursorPage<>(visible,more?paging.next(visible.getLast().publishedAt(),visible.getLast().id()):null);
    }
}
