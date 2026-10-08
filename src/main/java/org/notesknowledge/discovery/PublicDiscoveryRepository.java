package org.notesknowledge.discovery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.notesknowledge.discovery.spi.PublicDiscoveryScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL is Discovery-owned; authority arrives through the public-only Publishing SPI. */
@Repository
class PublicDiscoveryRepository {
    record Row(UUID id,long generation,String title,List<String> tags,Instant published,long likes,long views,double lexical,double fuzzy) {
        @Override public String toString(){return "PublicDiscoveryRow[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    PublicDiscoveryRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    private JdbcClient jdbc(){return clients.getObject();}
    List<UUID> inventory(UUID after) {
        var q=jdbc().sql("select publication_id from discovery.publication_projection where active"+(after==null?"":" and publication_id>:after")+" order by publication_id limit 100");
        if(after!=null)q=q.param("after",after);
        return q.query(UUID.class).list();
    }
    List<Row> rows(List<PublicDiscoveryScope.Current> scope,String query,String tag,Instant asOf) {
        if(scope.isEmpty())return List.of();
        String exact=java.util.stream.IntStream.range(0,scope.size()).mapToObj(i->"(publication_id=:id"+i+" and publication_generation=:g"+i+")").collect(java.util.stream.Collectors.joining(" or "));
        var q=jdbc().sql("""
            select publication_id,publication_generation,title,tags,published_at,like_count,view_count,
                case when :q='' then 0 else greatest(ts_rank_cd(to_tsvector('simple',title||' '||markdown)||to_tsvector('english',title||' '||markdown),
                    websearch_to_tsquery('simple',:q)||websearch_to_tsquery('english',:q)),
                    case when position(lower(:q) in lower(title||' '||markdown))>0 then 1 else 0 end) end as lexical,
                case when :q='' then 0 else greatest(word_similarity(:q,title),word_similarity(:q,markdown)) end as fuzzy
            from discovery.publication_projection where active and published_at<=:asof and (
            """+exact+") and (:tag='' or exists(select 1 from unnest(tags) t where lower(normalize(btrim(t),NFKC))=:tag))")
            .param("q",query).param("tag",tag).param("asof",Timestamp.from(asOf));
        for(int i=0;i<scope.size();i++)q=q.param("id"+i,scope.get(i).id()).param("g"+i,scope.get(i).generation());
        return q.query((r,i)->new Row(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),Arrays.asList((String[])r.getArray(4).getArray()),
            r.getTimestamp(5).toInstant(),r.getLong(6),r.getLong(7),r.getDouble(8),r.getDouble(9))).list();
    }
    boolean projectionCurrent(UUID id,long generation,boolean lock) {
        return jdbc().sql("select publication_id from discovery.publication_projection where publication_id=:id and publication_generation=:generation and active"+(lock?" for update":""))
            .param("id",id).param("generation",generation).query(UUID.class).optional().isPresent();
    }
    void like(UUID user,UUID publication,boolean desired) {
        if(desired)jdbc().sql("insert into discovery.publication_like(user_id,publication_id) values(:user,:id) on conflict(user_id,publication_id) do nothing")
            .param("user",user).param("id",publication).update();
        else jdbc().sql("delete from discovery.publication_like where user_id=:user and publication_id=:id").param("user",user).param("id",publication).update();
        // Projection row lock serializes Like/Unlike/count and generation denial. Recount is drift-safe.
        jdbc().sql("update discovery.publication_projection set like_count=(select count(*) from discovery.publication_like where publication_id=:id) where publication_id=:id and active").param("id",publication).update();
    }
    boolean liked(UUID viewer,UUID publication){return jdbc().sql("select exists(select 1 from discovery.publication_like where user_id=:user and publication_id=:id)").param("user",viewer).param("id",publication).query(Boolean.class).single();}
}
