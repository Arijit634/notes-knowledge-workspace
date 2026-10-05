package org.notesknowledge.notes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AttachmentRepository {
    static final long NOTE_BYTES = 50L * 1024 * 1024, OWNER_BYTES = 200L * 1024 * 1024;
    static final int NOTE_COUNT = 20, OWNER_COUNT = 200;
    private final ObjectProvider<JdbcClient> clients;
    AttachmentRepository(ObjectProvider<JdbcClient> clients) { this.clients = clients; }

    private static final String RETAINED_CORE = """
            select attachment_id, note_id, media_kind, display_filename, media_type, size_bytes,
                width, height, duration_seconds, page_count, storage_state, validation_state,
                cleanup_state, created_at, updated_at, revision
            from notes.attachment where owner_user_id = :owner and note_id = :note
                and cleanup_state = 'retained'
                and exists (select 1 from notes.note n where n.note_id = :note
                    and n.owner_user_id = :owner and n.lifecycle_state <> 'logically_deleted')
            """;

    Optional<Core> findRetained(UUID owner, UUID note, UUID attachment) {
        return clients.getObject().sql(RETAINED_CORE + " and attachment_id = :attachment")
                .param("owner", owner).param("note", note).param("attachment", attachment)
                .query(AttachmentRepository::core).optional();
    }

    List<Core> retainedPage(UUID owner, UUID note, Instant before, UUID beforeId, int count) {
        String continuation = before == null ? "" : " and (created_at, attachment_id) < (:before, :beforeId)";
        var query = clients.getObject().sql(RETAINED_CORE + continuation
                + " order by created_at desc, attachment_id desc limit :count")
                .param("owner", owner).param("note", note).param("count", count);
        if (before != null) query = query.param("before", Timestamp.from(before)).param("beforeId", beforeId);
        return query.query(AttachmentRepository::core).list();
    }

    record Core(AttachmentView view, long revision) { }

    Optional<AttachmentContentDescriptor> content(UUID owner, UUID note, UUID attachment) {
        return clients.getObject().sql("""
                select attachment_id, object_reference, media_type, display_filename, size_bytes, revision
                from notes.attachment where owner_user_id = :owner and note_id = :note
                    and attachment_id = :attachment and cleanup_state = 'retained'
                    and storage_state = 'stored' and validation_state = 'accepted' and size_bytes > 0
                    and exists (select 1 from notes.note n where n.note_id = :note
                        and n.owner_user_id = :owner and n.lifecycle_state <> 'logically_deleted')
                """).param("owner", owner).param("note", note).param("attachment", attachment)
                .query((row, index) -> new AttachmentContentDescriptor(row.getObject("attachment_id", UUID.class),
                        row.getString("object_reference"), row.getString("media_type"), row.getString("display_filename"),
                        row.getLong("size_bytes"), row.getLong("revision"))).optional();
    }

    private static Core core(ResultSet row, int index) throws SQLException {
        return new Core(new AttachmentView(row.getObject("attachment_id", UUID.class),
                row.getObject("note_id", UUID.class), row.getString("media_kind"),
                row.getString("display_filename"), row.getString("media_type"), row.getLong("size_bytes"),
                row.getObject("width", Integer.class), row.getObject("height", Integer.class),
                row.getObject("duration_seconds", Double.class), row.getObject("page_count", Integer.class),
                row.getString("storage_state"), row.getString("validation_state"), row.getString("cleanup_state"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()),
                row.getLong("revision"));
    }

    boolean fits(UUID owner, UUID note, long newBytes) {
        var usage = clients.getObject().sql("""
                select count(*) as owner_count, coalesce(sum(size_bytes), 0) as owner_bytes,
                    count(*) filter (where note_id = :note) as note_count,
                    coalesce(sum(size_bytes) filter (where note_id = :note), 0) as note_bytes
                from notes.attachment where owner_user_id = :owner and cleanup_state <> 'deleted'
                """).param("owner", owner).param("note", note).query((row, index) ->
                        new long[]{row.getLong("owner_count"), row.getLong("owner_bytes"),
                                row.getLong("note_count"), row.getLong("note_bytes")}).single();
        return usage[0] < OWNER_COUNT && usage[2] < NOTE_COUNT
                && newBytes <= OWNER_BYTES - usage[1] && newBytes <= NOTE_BYTES - usage[3];
    }

    void create(UUID id, UUID note, UUID owner, String reference, String display,
            AttachmentMediaValidator.Validated media, Instant now) {
        clients.getObject().sql("""
                insert into notes.attachment (attachment_id, note_id, owner_user_id, object_reference,
                    display_filename, media_kind, media_type, size_bytes, width, height, duration_seconds, page_count,
                    storage_state, validation_state, cleanup_state, revision, processing_generation, created_at, updated_at)
                values (:id, :note, :owner, :reference, :display, :kind, :type, :size, :width, :height, :duration, :pages,
                    'stored', 'accepted', 'retained', 1, 1, :now, :now)
                """).param("id", id).param("note", note).param("owner", owner).param("reference", reference)
                .param("display", display).param("kind", media.kind()).param("type", media.mediaType())
                .param("size", media.sizeBytes()).param("width", media.width(), java.sql.Types.INTEGER)
                .param("height", media.height(), java.sql.Types.INTEGER)
                .param("duration", media.durationSeconds(), java.sql.Types.DOUBLE)
                .param("pages", media.pageCount(), java.sql.Types.INTEGER).param("now", Timestamp.from(now)).update();
    }

    boolean referenced(String reference) {
        return clients.getObject().sql("select exists(select 1 from notes.attachment where object_reference = :reference)")
                .param("reference", reference).query(Boolean.class).single();
    }
}
