package org.notesknowledge.notes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/** All Note reads and writes start from the authenticated owner scope. */
@Repository
class NotesRepository {
    private final ObjectProvider<JdbcClient> clients;

    NotesRepository(ObjectProvider<JdbcClient> clients) { this.clients = clients; }

    private JdbcClient jdbc() { return clients.getObject(); }

    boolean preference(UUID owner) {
        return jdbc().sql("select default_ai_enabled from notes.note_preferences where user_id = :owner")
                .param("owner", owner).query(Boolean.class).optional().orElse(false);
    }

    void setPreference(UUID owner, boolean enabled, Instant now) {
        jdbc().sql("""
                insert into notes.note_preferences (user_id, default_ai_enabled, updated_at)
                values (:owner, :enabled, :now)
                on conflict (user_id) do update set
                    default_ai_enabled = excluded.default_ai_enabled,
                    updated_at = excluded.updated_at
                """).param("owner", owner).param("enabled", enabled)
                .param("now", Timestamp.from(now)).update();
    }

    void create(UUID id, UUID owner, String title, String markdown, boolean aiEnabled,
            Instant now) {
        jdbc().sql("""
                insert into notes.note (note_id, owner_user_id, title, markdown,
                    lifecycle_state, pinned, revision, ai_enabled, ai_generation,
                    created_at, updated_at)
                values (:id, :owner, :title, :markdown, 'active', false, 1,
                    :aiEnabled, 1, :now, :now)
                """).param("id", id).param("owner", owner).param("title", title)
                .param("markdown", markdown).param("aiEnabled", aiEnabled)
                .param("now", Timestamp.from(now)).update();
    }

    Optional<NoteRecord> find(UUID owner, UUID id) {
        return jdbc().sql("""
                select note_id, title, markdown, lifecycle_state, pinned, ai_enabled,
                       created_at, updated_at, revision,
                       ARRAY(select t.display_label from notes.note_tag t
                             where t.note_id = n.note_id and t.owner_user_id = n.owner_user_id
                             order by t.normalized_label) as tags
                from notes.note n where owner_user_id = :owner and note_id = :id
                  and lifecycle_state <> 'logically_deleted'
                """).param("owner", owner).param("id", id)
                .query(NotesRepository::map).optional();
    }

    Optional<NoteRecord> lock(UUID owner, UUID id) {
        // A separate post-lock read gets a fresh READ COMMITTED snapshot if the
        // lock waited for another tag command; root and children stay consistent.
        var locked = jdbc().sql("""
                select note_id from notes.note
                where owner_user_id = :owner and note_id = :id
                  and lifecycle_state <> 'logically_deleted' for update
                """).param("owner", owner).param("id", id).query(UUID.class).optional();
        return locked.isPresent() ? find(owner, id) : Optional.empty();
    }

    void replaceTags(UUID owner, UUID id, List<TagLabel> tags, Instant now) {
        jdbc().sql("delete from notes.note_tag where note_id = :id and owner_user_id = :owner")
                .param("id", id).param("owner", owner).update();
        for (TagLabel tag : tags) {
            jdbc().sql("""
                    insert into notes.note_tag
                        (note_id, owner_user_id, normalized_label, display_label, created_at)
                    values (:id, :owner, :normalized, :display, :now)
                    """).param("id", id).param("owner", owner)
                    .param("normalized", tag.normalized()).param("display", tag.display())
                    .param("now", Timestamp.from(now)).update();
        }
    }

    int advanceTagRevision(UUID owner, UUID id, long revision, Instant now) {
        return jdbc().sql("""
                update notes.note set revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state in ('active', 'archived')
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("now", Timestamp.from(now)).update();
    }

    int save(UUID owner, UUID id, long revision, String title, String markdown,
            Instant now) {
        return jdbc().sql("""
                update notes.note set title = :title, markdown = :markdown,
                    revision = revision + 1, updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id
                  and revision = :revision and lifecycle_state = 'active'
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("title", title).param("markdown", markdown)
                .param("now", Timestamp.from(now)).update();
    }

    int setPin(UUID owner, UUID id, long revision, boolean pinned, Instant now) {
        return jdbc().sql("""
                update notes.note set pinned = :pinned, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state in ('active', 'archived') and pinned <> :pinned
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("pinned", pinned).param("now", Timestamp.from(now)).update();
    }

    int transitionLifecycle(UUID owner, UUID id, long revision, String from,
            String to, Instant now) {
        return jdbc().sql("""
                update notes.note set lifecycle_state = :to, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state = :from
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("from", from).param("to", to).param("now", Timestamp.from(now)).update();
    }

    List<NoteRecord> page(UUID owner, String lifecycle, Boolean pinned, Instant beforeTime,
            UUID beforeId, int count) {
        String pinFilter = pinned == null ? "" : " and pinned = :pinned\n";
        String continuation = beforeTime == null ? "" : """
                  and (updated_at < :beforeTime
                       or (updated_at = :beforeTime and note_id < :beforeId))
                """;
        var statement = jdbc().sql("""
                select note_id, title, markdown, lifecycle_state, pinned, ai_enabled,
                       created_at, updated_at, revision,
                       ARRAY(select t.display_label from notes.note_tag t
                             where t.note_id = n.note_id and t.owner_user_id = n.owner_user_id
                             order by t.normalized_label) as tags
                from notes.note n
                where owner_user_id = :owner and lifecycle_state = :lifecycle
                """ + pinFilter + continuation + """
                order by updated_at desc, note_id desc
                limit :count
                """).param("owner", owner).param("lifecycle", lifecycle)
                .param("count", count);
        if (beforeTime != null) {
            statement = statement.param("beforeTime", Timestamp.from(beforeTime))
                    .param("beforeId", beforeId);
        }
        if (pinned != null) {
            statement = statement.param("pinned", pinned);
        }
        return statement.query(NotesRepository::map).list();
    }

    private static NoteRecord map(ResultSet row, int index) throws SQLException {
        return new NoteRecord(row.getObject("note_id", UUID.class), row.getString("title"),
                row.getString("markdown"), row.getString("lifecycle_state"),
                row.getBoolean("pinned"), row.getBoolean("ai_enabled"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getLong("revision"),
                List.copyOf(Arrays.asList((String[]) row.getArray("tags").getArray())));
    }
}
