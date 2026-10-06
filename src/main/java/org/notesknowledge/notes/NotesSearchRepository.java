package org.notesknowledge.notes;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Each signal starts in owner/lifecycle scope, before its scoring and budget. */
@Repository
class NotesSearchRepository {
    record Row(NoteSearchResult result, long rank) { }
    private final ObjectProvider<JdbcClient> clients;
    private final NotesSearchProperties policy;
    NotesSearchRepository(ObjectProvider<JdbcClient> clients, NotesSearchProperties policy) {
        this.clients = clients; this.policy = policy;
    }

    private static final String SCOPE = """
            n.owner_user_id = :owner and n.lifecycle_state = :lifecycle
            and not exists (select 1 from unnest(cast(:tags as text[])) wanted(label)
                where not exists (select 1 from notes.note_tag t
                    where t.owner_user_id = :owner and t.note_id = n.note_id
                      and t.normalized_label = wanted.label))
            """;
    private static final String TAG_MATCH = """
            exists (select 1 from notes.note_tag t
                where t.owner_user_id = :owner and t.note_id = n.note_id
                  and (to_tsvector('simple', t.normalized_label) @@ q.simple
                       or t.normalized_label = :query or t.normalized_label = :variant))
            """;

    // Materialize only already bounded signal lists. No global corpus CTE/post-filter.
    static String sql(boolean continuation) {
        String page = continuation ? "where (f.rank, n.note_id) < (:beforeRank, :beforeId)" : "";
        return """
            with q as (select plainto_tsquery('simple', :query) || plainto_tsquery('simple', :variant) as simple,
                       plainto_tsquery('english', :query) || plainto_tsquery('english', :variant) as english),
            simple_hits as materialized (
              select n.note_id, n.revision, ts_rank(n.search_simple, q.simple) as score
              from notes.note n cross join q where
            """ + SCOPE + """
              and n.search_simple @@ q.simple order by score desc, n.note_id desc limit :budget),
            english_hits as materialized (
              select n.note_id, n.revision, ts_rank(n.search_english, q.english) as score
              from notes.note n cross join q where
            """ + SCOPE + """
              and n.search_english @@ q.english order by score desc, n.note_id desc limit :budget),
            fuzzy_hits as materialized (
              select n.note_id, n.revision,
                     4 * word_similarity(:query, n.search_title) + word_similarity(:query, n.search_body) as score
              from notes.note n where
            """ + SCOPE + """
              and char_length(:query) >= 3 and (n.search_text %> :query or n.search_title %> :query)
              order by score desc, n.note_id desc limit :budget),
            tag_hits as materialized (
              select n.note_id, n.revision, 1 as score from notes.note n cross join q where
            """ + SCOPE + " and " + TAG_MATCH + """
              order by n.note_id desc limit :budget),
            ranked as (
              select note_id, revision, 1000000000 / (60 + row_number() over(order by score desc, note_id desc)) as value from simple_hits
              union all
              select note_id, revision, 1000000000 / (60 + row_number() over(order by score desc, note_id desc)) from english_hits
              union all
              select note_id, revision, 1000000000 / (60 + row_number() over(order by score desc, note_id desc)) from fuzzy_hits
              union all
              select note_id, revision, 1500000000 / (60 + row_number() over(order by score desc, note_id desc)) from tag_hits),
            fused as materialized (
              select note_id, revision, sum(value)::bigint as rank from ranked
              group by note_id, revision order by rank desc, note_id desc limit 100)
            select n.note_id, n.title, n.lifecycle_state, n.pinned, n.updated_at, f.rank,
                   substring(n.search_body from greatest(1,
                       coalesce(nullif(strpos(n.search_body, :query), 0),
                                nullif(strpos(n.search_body, :variant), 0), 1) - 60)
                       for 240) as snippet_source,
                   array(select t.display_label from notes.note_tag t where t.owner_user_id = :owner
                       and t.note_id = n.note_id order by t.normalized_label) as tags,
                   array_remove(array[
                     case when ts_filter(n.search_simple, '{A}') @@ q.simple
                         or ts_filter(n.search_english, '{A}') @@ q.english
                         or (char_length(:query) >= 3 and word_similarity(:query,n.search_title) >= :threshold) then 'title' end,
                     case when ts_filter(n.search_simple, '{D}') @@ q.simple
                         or ts_filter(n.search_english, '{D}') @@ q.english
                         or (char_length(:query) >= 3 and word_similarity(:query,n.search_body) >= :threshold) then 'body' end,
                     case when
            """ + TAG_MATCH + """
                         then 'tag' end], null) as labels
            from fused f join notes.note n on n.note_id = f.note_id and n.revision = f.revision
              and n.owner_user_id = :owner and n.lifecycle_state = :lifecycle
            cross join q
            """ + page + " order by f.rank desc, n.note_id desc limit :count";
    }

    @Transactional(readOnly = true)
    List<Row> search(UUID owner, PrivateSearchRequest request, Long beforeRank, UUID beforeId, int count) {
        var jdbc = clients.getObject();
        // Transaction-local, bounded policy, restored by PostgreSQL at transaction end.
        jdbc.sql("select set_config('pg_trgm.word_similarity_threshold', :value, true)")
                .param("value", Double.toString(policy.wordSimilarityThreshold())).query(String.class).single();
        jdbc.sql("select set_config('statement_timeout', :value, true)")
                .param("value", Integer.toString(policy.timeoutMillis())).query(String.class).single();
        var statement = jdbc.sql(sql(beforeRank != null)).param("owner", owner)
                .param("lifecycle", request.lifecycle()).param("query", request.query())
                .param("variant", request.lexicalVariant()).param("tags", new org.springframework.jdbc.core.SqlParameterValue(
                        java.sql.Types.ARRAY, request.tags().toArray(String[]::new)))
                .param("budget", policy.candidatesPerSignal()).param("count", count)
                .param("threshold", policy.wordSimilarityThreshold());
        if (beforeRank != null) statement = statement.param("beforeRank", beforeRank).param("beforeId", beforeId);
        return statement.query((row, index) -> new Row(new NoteSearchResult(
                row.getObject("note_id", UUID.class), row.getString("title"), row.getString("lifecycle_state"),
                row.getBoolean("pinned"), List.copyOf(Arrays.asList((String[]) row.getArray("tags").getArray())),
                row.getTimestamp("updated_at").toInstant(),
                SearchTextProjection.snippet(row.getString("snippet_source")),
                List.copyOf(Arrays.asList((String[]) row.getArray("labels").getArray()))), row.getLong("rank"))).list();
    }
}
