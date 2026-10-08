package org.notesknowledge.profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PublicProfileRepository {
    record Source(UUID profile,UUID owner,String handle,String name,String biography,UUID avatar,String reference,
                  String type,Long size,Integer width,Integer height,long generation) {
        @Override public String toString(){return "PublicProfileSource[REDACTED]";}
    }
    record Projection(UUID id,UUID owner,String handle,String displayName,String biography,long generation,
                      boolean active,UUID avatar,UUID sourceAvatar,String reference,String type,Long size,Integer width,Integer height) {
        @Override public String toString(){return "PublicProfileProjection[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    PublicProfileRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    JdbcClient jdbc(){return clients.getObject();}
    Source source(UUID owner,boolean lock) {
        if(lock) jdbc().sql("select profile_id from profile.profile where user_id=:owner for update")
            .param("owner",owner).query(UUID.class).optional().orElseThrow(PublicProfileRepository::required);
        return jdbc().sql("""
            select p.profile_id,p.user_id,p.public_handle_normalized,p.display_name,p.biography,
                a.avatar_asset_id,a.object_reference,a.media_type,a.byte_size,a.width,a.height,
                coalesce(q.projection_generation,0) as generation
            from profile.profile p left join profile.avatar_asset a on a.profile_id=p.profile_id
                and a.avatar_asset_id=p.selected_avatar_id and a.state='validated'
            left join profile.public_profile_projection q on q.profile_id=p.profile_id where p.user_id=:owner
            """).param("owner",owner).query((r,i)->new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),r.getString(7),r.getString(8),
                r.getObject(9,Long.class),r.getObject(10,Integer.class),r.getObject(11,Integer.class),r.getLong(12)))
            .optional().orElseThrow(PublicProfileRepository::required);
    }
    Optional<Projection> owner(UUID owner){return projection("user_id=:value",owner);}
    Optional<Projection> id(UUID id){return projection("public_profile_projection_id=:value and active",id);}
    Optional<Projection> handle(String handle){return projection("handle=:value and active",handle);}
    private Optional<Projection> projection(String predicate,Object value) {
        return jdbc().sql("select * from profile.public_profile_projection where "+predicate).param("value",value)
            .query((r,i)->new Projection(r.getObject("public_profile_projection_id",UUID.class),r.getObject("user_id",UUID.class),
                r.getString("handle"),r.getString("display_name"),r.getString("biography"),r.getLong("projection_generation"),
                r.getBoolean("active"),r.getObject("public_avatar_id",UUID.class),r.getObject("source_avatar_id",UUID.class),
                r.getString("public_avatar_object_reference"),r.getString("avatar_media_type"),r.getObject("avatar_byte_size",Long.class),
                r.getObject("avatar_width",Integer.class),r.getObject("avatar_height",Integer.class))).optional();
    }
    void activate(UUID id,Source source,UUID avatar,String reference,Instant now) {
        jdbc().sql("""
            insert into profile.public_profile_projection(public_profile_projection_id,profile_id,user_id,handle,display_name,
                biography,projection_generation,active,activated_at,updated_at,public_avatar_id,source_avatar_id,
                public_avatar_object_reference,avatar_media_type,avatar_byte_size,avatar_width,avatar_height)
            values(:id,:profile,:owner,:handle,:name,:bio,1,true,:now,:now,:avatar,:source,:reference,:type,:size,:width,:height)
            on conflict(profile_id) do update set handle=excluded.handle,display_name=excluded.display_name,
                biography=excluded.biography,projection_generation=public_profile_projection.projection_generation+1,
                active=true,updated_at=greatest(public_profile_projection.updated_at,excluded.updated_at),
                public_avatar_id=excluded.public_avatar_id,source_avatar_id=excluded.source_avatar_id,
                public_avatar_object_reference=excluded.public_avatar_object_reference,avatar_media_type=excluded.avatar_media_type,
                avatar_byte_size=excluded.avatar_byte_size,avatar_width=excluded.avatar_width,avatar_height=excluded.avatar_height
            """).param("id",id).param("profile",source.profile()).param("owner",source.owner()).param("handle",source.handle())
            .param("name",source.name()).param("bio",source.biography()).param("now",Timestamp.from(now))
            .param("avatar",avatar).param("source",source.avatar()).param("reference",reference)
            .param("type",avatar==null?null:source.type()).param("size",avatar==null?null:source.size())
            .param("width",avatar==null?null:source.width()).param("height",avatar==null?null:source.height()).update();
    }
    void inactivate(UUID owner,Instant now) {
        jdbc().sql("update profile.public_profile_projection set active=false,projection_generation=projection_generation+1,updated_at=greatest(updated_at,:now) where user_id=:owner and active")
            .param("owner",owner).param("now",Timestamp.from(now)).update();
    }
    boolean referenced(String reference){return jdbc().sql("select exists(select 1 from profile.public_profile_projection where public_avatar_object_reference=:reference and active)")
        .param("reference",reference).query(Boolean.class).single();}
    static ApiFailureException required(){return ApiFailureException.of(ApiFailureException.Kind.PUBLIC_PROFILE_REQUIRED);}
}
