package org.notesknowledge.notes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NoteVersionRepository {
    private final ObjectProvider<JdbcClient> clients;
    NoteVersionRepository(ObjectProvider<JdbcClient> clients) { this.clients = clients; }
    private JdbcClient jdbc() { return clients.getObject(); }

    Optional<NoteVersionRecord> find(UUID owner, UUID note, UUID version) {
        return jdbc().sql("""
                select note_version_id,title,markdown,source_revision,checkpoint_kind,created_at
                from notes.note_version where owner_user_id=:owner and note_id=:note and note_version_id=:version
                  and exists (select 1 from notes.note n where n.note_id=:note and n.owner_user_id=:owner
                    and n.lifecycle_state <> 'logically_deleted')
                """).param("owner", owner).param("note", note).param("version", version)
                .query((row, index) -> new NoteVersionRecord(row.getObject("note_version_id", UUID.class),
                        row.getString("title"), row.getString("markdown"), row.getLong("source_revision"),
                        row.getString("checkpoint_kind"), row.getTimestamp("created_at").toInstant())).optional();
    }

    Optional<UUID> equivalent(UUID owner, NoteRecord saved) {
        return jdbc().sql("""
                select note_version_id from notes.note_version
                where owner_user_id=:owner and note_id=:note and title=:title and markdown=:markdown
                order by created_at desc,note_version_id desc limit 1
                """).param("owner", owner).param("note", saved.id()).param("title", saved.title())
                .param("markdown", saved.markdown()).query(UUID.class).optional();
    }

    Optional<Instant> latestTime(UUID owner, UUID note) {
        return jdbc().sql("""
                select created_at from notes.note_version where owner_user_id=:owner and note_id=:note
                order by created_at desc,note_version_id desc limit 1
                """).param("owner", owner).param("note", note).query(Timestamp.class)
                .optional().map(Timestamp::toInstant);
    }

    void insert(UUID owner, UUID version, NoteRecord saved, String kind, Instant now) {
        jdbc().sql("""
                insert into notes.note_version(note_version_id,note_id,owner_user_id,title,markdown,
                    source_revision,checkpoint_kind,created_at)
                values (:version,:note,:owner,:title,:markdown,:revision,:kind,:now)
                """).param("version", version).param("note", saved.id()).param("owner", owner)
                .param("title", saved.title()).param("markdown", saved.markdown()).param("revision", saved.revision())
                .param("kind", kind).param("now", Timestamp.from(now)).update();
    }

    List<NoteVersionRecord.Summary> page(UUID owner, UUID note, Instant before, UUID beforeId, int count) {
        String continuation = before == null ? "" : " and (created_at < :before or (created_at=:before and note_version_id < :beforeId))";
        var query = jdbc().sql("""
                select note_version_id,title,source_revision,checkpoint_kind,created_at from notes.note_version
                where owner_user_id=:owner and note_id=:note
                  and exists (select 1 from notes.note n where n.note_id=:note and n.owner_user_id=:owner
                    and n.lifecycle_state <> 'logically_deleted')
                """ + continuation + " order by created_at desc,note_version_id desc limit :count")
                .param("owner", owner).param("note", note).param("count", count);
        if (before != null) query = query.param("before", Timestamp.from(before)).param("beforeId", beforeId);
        return query.query((row, index) -> new NoteVersionRecord.Summary(row.getObject("note_version_id", UUID.class),
                row.getString("title"), row.getLong("source_revision"), row.getString("checkpoint_kind"),
                row.getTimestamp("created_at").toInstant())).list();
    }

    // Caller holds the owning Note lock. Held checkpoints are not part of the unheld quota.
    int compact(UUID owner, UUID note, int maxUnheld, List<UUID> protectedVersions) {
        int protectedCount = jdbc().sql("""
                select count(*) from notes.note_version v where owner_user_id=:owner and note_id=:note
                  and note_version_id in (:protected) and not exists
                    (select 1 from notes.note_version_hold h where h.note_version_id=v.note_version_id)
                """).param("owner", owner).param("note", note).param("protected", protectedVersions)
                .query(Integer.class).single();
        return jdbc().sql("""
                delete from notes.note_version where owner_user_id=:owner and note_id=:note
                  and note_version_id in (
                    select v.note_version_id from notes.note_version v
                    where owner_user_id=:owner and note_id=:note and note_version_id not in (:protected)
                      and not exists (select 1 from notes.note_version_hold h where h.note_version_id=v.note_version_id)
                    order by created_at desc,note_version_id desc offset :keep)
                """).param("owner", owner).param("note", note).param("protected", protectedVersions)
                .param("keep", Math.max(0, maxUnheld - protectedCount)).update();
    }

    void acquirePublicationHold(UUID owner, UUID note, UUID version, UUID holder, Instant now) {
        jdbc().sql("""
                insert into notes.note_version_hold(note_version_id,note_id,owner_user_id,holder_kind,holder_id,created_at)
                values (:version,:note,:owner,'publication',:holder,:now)
                on conflict (note_version_id,holder_kind,holder_id) do nothing
                """).param("version", version).param("note", note).param("owner", owner)
                .param("holder", holder).param("now", Timestamp.from(now)).update();
    }

    void releasePublicationHold(UUID owner, UUID note, UUID version, UUID holder) {
        jdbc().sql("""
                delete from notes.note_version_hold where owner_user_id=:owner and note_id=:note
                  and note_version_id=:version and holder_kind='publication' and holder_id=:holder
                """).param("owner", owner).param("note", note).param("version", version).param("holder", holder).update();
    }
}
