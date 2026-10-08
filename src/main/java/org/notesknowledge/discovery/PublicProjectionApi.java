package org.notesknowledge.discovery;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Discovery owns synchronous current-generation public projection authority. */
@Service
public class PublicProjectionApi {
    public record Snapshot(UUID publication,UUID author,long generation,String title,String markdown,List<String> tags,
        Instant publishedAt,Instant updatedAt) {
        @Override public String toString(){return "PublicProjectionSnapshot[REDACTED]";}
    }
    public record Engagement(long likeCount,long approximateViewCount) { }
    private final ObjectProvider<JdbcClient> clients;
    private final Clock clock;
    PublicProjectionApi(ObjectProvider<JdbcClient> clients,Clock clock){this.clients=clients;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void advance(Snapshot s) {
        int changed=clients.getObject().sql("""
            insert into discovery.publication_projection(publication_id,public_profile_projection_id,publication_generation,
                active,title,markdown,tags,published_at,updated_at)
            values(:id,:author,:generation,true,:title,:markdown,:tags,:published,:updated)
            on conflict(publication_id) do update set public_profile_projection_id=excluded.public_profile_projection_id,
                publication_generation=excluded.publication_generation,active=true,title=excluded.title,
                markdown=excluded.markdown,tags=excluded.tags,updated_at=excluded.updated_at
            where publication_projection.publication_generation < excluded.publication_generation
            """).param("id",s.publication()).param("author",s.author()).param("generation",s.generation())
            .param("title",s.title()).param("markdown",s.markdown()).param("tags",s.tags().toArray(String[]::new),java.sql.Types.ARRAY)
            .param("published",Timestamp.from(s.publishedAt())).param("updated",Timestamp.from(s.updatedAt())).update();
        if(changed!=1)throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void invalidate(UUID publication,long generation,Instant now) {
        int changed=clients.getObject().sql("""
            update discovery.publication_projection set active=false,publication_generation=:generation,updated_at=greatest(updated_at,:now)
            where publication_id=:id and publication_generation < :generation
            """).param("id",publication).param("generation",generation).param("now",Timestamp.from(now)).update();
        if(changed!=1)throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
    }
    @Transactional(readOnly=true)
    public Engagement engagement(UUID publication,long generation) {
        return clients.getObject().sql("select like_count,view_count from discovery.publication_projection where publication_id=:id and publication_generation=:generation and active")
            .param("id",publication).param("generation",generation).query((r,i)->new Engagement(r.getLong(1),r.getLong(2)))
            .optional().orElseThrow(()->ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
    }
    @Transactional
    public void recordApproximateView(UUID publication,long generation) {
        // Serialize with projection invalidation; failure is isolated from the public read's transaction.
        var current=clients.getObject().sql("select publication_id from discovery.publication_projection where publication_id=:id and publication_generation=:generation and active for update")
            .param("id",publication).param("generation",generation).query(UUID.class).optional();
        if(current.isEmpty())return;
        var now=Timestamp.from(clock.instant());
        clients.getObject().sql("""
            insert into discovery.publication_view_aggregate(publication_id,bucket_date,view_count,updated_at)
            values(:id,(cast(:now as timestamptz) at time zone 'UTC')::date,1,:now)
            on conflict(publication_id,bucket_date) do update set view_count=least(publication_view_aggregate.view_count,9223372036854775806)+1,updated_at=excluded.updated_at
            """).param("id",publication).param("now",now).update();
        clients.getObject().sql("update discovery.publication_projection set view_count=least(view_count,9223372036854775806)+1 where publication_id=:id")
            .param("id",publication).update();
    }
}
