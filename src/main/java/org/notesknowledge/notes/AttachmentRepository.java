package org.notesknowledge.notes;

import java.sql.Timestamp;
import java.time.Instant;
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
