package org.notesknowledge.profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AvatarAssetRepository {
    private final ObjectProvider<JdbcClient> jdbc;
    AvatarAssetRepository(ObjectProvider<JdbcClient> jdbc) { this.jdbc = jdbc; }

    AvatarSummary summary(UUID profile) {
        return jdbc.getObject().sql("""
                select a.media_type, a.width, a.height, a.byte_size, a.created_at
                from profile.profile p join profile.avatar_asset a
                  on a.profile_id=p.profile_id and a.avatar_asset_id=p.selected_avatar_id
                where p.profile_id=:profile and a.state='validated'
                """).param("profile", profile).query((row, index) -> new AvatarSummary(row.getString(1),
                    row.getInt(2), row.getInt(3), row.getLong(4), row.getTimestamp(5).toInstant())).optional().orElse(null);
    }

    UUID lockOrCreate(UUID id, UUID owner, Instant now) {
        jdbc.getObject().sql("""
                insert into profile.profile(profile_id,user_id,display_name,biography,updated_at)
                values (:id,:owner,'','',:now) on conflict(user_id) do nothing
                """).param("id", id).param("owner", owner).param("now", Timestamp.from(now)).update();
        return lock(owner).orElseThrow();
    }

    Optional<UUID> lock(UUID owner) {
        return jdbc.getObject().sql("select profile_id from profile.profile where user_id=:owner for update")
                .param("owner", owner).query(UUID.class).optional();
    }

    AvatarAsset selected(UUID profile) {
        return jdbc.getObject().sql("""
                select a.avatar_asset_id,a.object_reference from profile.avatar_asset a
                join profile.profile p on p.profile_id=a.profile_id and p.selected_avatar_id=a.avatar_asset_id
                where p.profile_id=:profile
                """).param("profile", profile).query((row, index) ->
                    new AvatarAsset(row.getObject(1, UUID.class), row.getString(2))).optional().orElse(null);
    }

    void create(UUID id, UUID profile, String reference, AvatarValidator.Canonical image, Instant now) {
        jdbc.getObject().sql("""
                insert into profile.avatar_asset(avatar_asset_id,profile_id,object_reference,state,
                    media_type,byte_size,width,height,display_filename,created_at)
                values(:id,:profile,:reference,'validated',:type,:size,:width,:height,:filename,:now)
                """).param("id", id).param("profile", profile).param("reference", reference)
                .param("type", image.mediaType()).param("size", image.bytes().length)
                .param("width", image.width()).param("height", image.height()).param("filename", image.filename())
                .param("now", Timestamp.from(now)).update();
    }

    void select(UUID profile, UUID asset, Instant now) {
        jdbc.getObject().sql("""
                update profile.profile set selected_avatar_id=:asset,
                    updated_at=greatest(updated_at,:now) where profile_id=:profile
                """).param("profile", profile).param("asset", asset).param("now", Timestamp.from(now)).update();
    }

    void retire(AvatarAsset asset, Instant now) {
        if (asset != null) jdbc.getObject().sql("""
                update profile.avatar_asset set state='removed',removed_at=greatest(created_at,:now)
                where avatar_asset_id=:id and state='validated'
                """).param("id", asset.id()).param("now", Timestamp.from(now)).update();
    }

    List<AvatarAsset> pending(int limit) {
        return jdbc.getObject().sql("""
                select avatar_asset_id,object_reference from profile.avatar_asset
                where state='removed' and cleaned_at is null order by removed_at,avatar_asset_id limit :limit
                """).param("limit", limit).query((row,index) -> new AvatarAsset(row.getObject(1, UUID.class), row.getString(2))).list();
    }

    void cleaned(AvatarAsset asset, Instant now) {
        jdbc.getObject().sql("""
                update profile.avatar_asset set cleaned_at=greatest(removed_at,:now)
                where avatar_asset_id=:id and state='removed' and cleaned_at is null
                """).param("id", asset.id()).param("now", Timestamp.from(now)).update();
    }

    boolean referenced(String reference) {
        return jdbc.getObject().sql("select exists(select 1 from profile.avatar_asset where object_reference=:reference)")
                .param("reference", reference).query(Boolean.class).single();
    }
}
