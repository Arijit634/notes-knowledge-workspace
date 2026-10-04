package org.notesknowledge.profile;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JpaProfileRepositoryAdapter {
    private final ObjectProvider<ProfileRepository> profiles;
    private final ObjectProvider<JdbcClient> jdbc;

    JpaProfileRepositoryAdapter(ObjectProvider<ProfileRepository> profiles, ObjectProvider<JdbcClient> jdbc) {
        this.profiles = profiles;
        this.jdbc = jdbc;
    }

    Optional<Profile> find(UUID owner) { return profiles.getObject().findByUserId(owner); }

    void replace(Profile profile) {
        ProfileView value = profile.view();
        // One owner-scoped upsert serializes first-write races and replacement.
        // The unique handle constraint, not a pre-check, decides competing claims.
        jdbc.getObject().sql("""
                insert into profile.profile (profile_id, user_id, display_name, biography,
                    public_handle_original, public_handle_normalized, updated_at)
                values (:id, :owner, :name, :bio, :original, :normalized, :now)
                on conflict (user_id) do update set display_name = excluded.display_name,
                    biography = excluded.biography,
                    public_handle_original = excluded.public_handle_original,
                    public_handle_normalized = excluded.public_handle_normalized,
                    updated_at = greatest(profile.updated_at, excluded.updated_at)
                """).param("id", profile.id()).param("owner", profile.userId())
                .param("name", value.displayName()).param("bio", value.biography())
                .param("original", value.handle()).param("normalized", profile.normalizedHandle())
                .param("now", Timestamp.from(value.updatedAt())).update();
    }
}
