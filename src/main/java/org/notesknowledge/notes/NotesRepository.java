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
    private final org.notesknowledge.knowledge.KnowledgeInvalidationApi invalidation;

    NotesRepository(ObjectProvider<JdbcClient> clients,org.notesknowledge.knowledge.KnowledgeInvalidationApi invalidation) {
        this.clients = clients;this.invalidation=invalidation;
    }

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
        int changed=jdbc().sql("""
                update notes.note set revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state in ('active', 'archived')
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int save(UUID owner, UUID id, long revision, String title, String markdown,
            Instant now) {
        int changed=jdbc().sql("""
                update notes.note set title = :title, markdown = :markdown,
                    revision = revision + 1, updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id
                  and revision = :revision and lifecycle_state = 'active'
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("title", title).param("markdown", markdown)
                .param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int setPin(UUID owner, UUID id, long revision, boolean pinned, Instant now) {
        int changed=jdbc().sql("""
                update notes.note set pinned = :pinned, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state in ('active', 'archived') and pinned <> :pinned
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("pinned", pinned).param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int transitionLifecycle(UUID owner, UUID id, long revision, String from,
            String to, Instant now) {
        int changed=jdbc().sql("""
                update notes.note set lifecycle_state = :to, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state = :from
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("from", from).param("to", to).param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int trash(UUID owner, UUID id, long revision, Instant now) {
        int changed=jdbc().sql("""
                update notes.note set pre_trash_state = lifecycle_state,
                    lifecycle_state = 'trashed', trashed_at = :now, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state in ('active', 'archived')
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int restore(UUID owner, UUID id, long revision, Instant now) {
        int changed=jdbc().sql("""
                update notes.note set lifecycle_state = pre_trash_state,
                    pre_trash_state = null, trashed_at = null, revision = revision + 1,
                    updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state = 'trashed' and pre_trash_state in ('active', 'archived')
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    int logicallyDelete(UUID owner, UUID id, long revision, Instant now) {
        int changed=jdbc().sql("""
                update notes.note set lifecycle_state = 'logically_deleted', deleted_at = :now,
                    pre_trash_state = null, trashed_at = null, revision = revision + 1,
                    ai_generation = ai_generation + 1, updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id = :id and revision = :revision
                  and lifecycle_state = 'trashed'
                """).param("owner", owner).param("id", id).param("revision", revision)
                .param("now", Timestamp.from(now)).update();
        return changed(owner,id,changed);
    }

    Optional<UUID> aiAccessUpperBound(UUID owner) {
        return jdbc().sql("""
                select note_id from notes.note where owner_user_id = :owner
                  and lifecycle_state <> 'logically_deleted' order by note_id desc limit 1
                """).param("owner", owner).query(UUID.class).optional();
    }

    List<UUID> lockAiAccessBatch(UUID owner, boolean enabled, List<UUID> selection,
            UUID after, UUID upper, int limit) {
        String selected = selection.isEmpty() ? "" : " and note_id in (:selection)";
        String continuation = after == null ? "" : " and note_id > :after";
        var query = jdbc().sql("""
                select note_id from notes.note where owner_user_id = :owner
                  and lifecycle_state <> 'logically_deleted' and ai_enabled <> :enabled
                  and note_id <= :upper
                """ + selected + continuation + " order by note_id limit :limit for update")
                .param("owner", owner).param("enabled", enabled).param("upper", upper).param("limit", limit);
        if (!selection.isEmpty()) query = query.param("selection", selection);
        if (after != null) query = query.param("after", after);
        return query.query(UUID.class).list();
    }

    int setAiAccess(UUID owner, List<UUID> ids, boolean enabled, Instant now) {
        // Both real transitions get fresh lineage; OFF/ON cannot reactivate old work.
        // Locks serialize against Save/tags/lifecycle; only these AI-owned fields change.
        List<UUID> changed=jdbc().sql("""
                update notes.note set ai_enabled = :enabled, ai_generation = ai_generation + 1,
                    revision = revision + 1, updated_at = greatest(updated_at, :now)
                where owner_user_id = :owner and note_id in (:ids)
                  and lifecycle_state <> 'logically_deleted' and ai_enabled <> :enabled
                returning note_id
                """).param("owner", owner).param("ids", ids).param("enabled", enabled)
                .param("now", Timestamp.from(now)).query(UUID.class).list();
        for(UUID id:changed)invalidation.noteChanged(owner,id);
        return changed.size();
    }

    private int changed(UUID owner,UUID id,int changed) {
        // Single owner-module mutation gateway covers Save, version restore, tags,
        // pin, lifecycle and AI commands in their caller's existing transaction.
        if(changed>0)invalidation.noteChanged(owner,id);
        return changed;
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
