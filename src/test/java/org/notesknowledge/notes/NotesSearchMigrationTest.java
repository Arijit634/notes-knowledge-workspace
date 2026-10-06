package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("RETRIEVAL")
@Testcontainers
class NotesSearchMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("search_upgrade").withUsername("search_migrator").withPassword("synthetic-search-migrator-password");
    @Test void upgradesExistingRowsInBoundedBatchesWithoutChangingRevisionOrTimestamps() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("12").load().migrate();
        UUID owner=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),'synthetic@example.test','synthetic@example.test',now(),'active',now(),now()) returning user_id",UUID.class);
        jdbc.update("""
                insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at)
                select uuidv7(),?, 'Café 東京', '# **Travel** `ClientFactory` https://example.test/path',
                    'active',7,false,3,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z'
                from generate_series(1,1003)
                """,owner);
        var flyway=Flyway.configure().dataSource(ds).target("13").load();
        String large = largeBody("prefixneedle");
        assertThat(large).hasSize(1_000_000);
        // The old full-body projection exceeds PostgreSQL's vector limit, not just a repeated word.
        Throwable overflow=org.assertj.core.api.Assertions.catchThrowable(() ->
                jdbc.queryForObject("select to_tsvector('simple', ?)",String.class,large));
        assertThat(overflow).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(((java.sql.SQLException)overflow.getCause()).getSQLState()).isEqualTo("54000");
        UUID largeId=jdbc.queryForObject("""
                insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at)
                values(uuidv7(),?,'Large fixture',?,'active',7,false,3,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z') returning note_id
                """,UUID.class,owner,large);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notes.note where search_text is not null and search_simple @@ plainto_tsquery('simple','東京') and search_english @@ plainto_tsquery('english','travels') and revision=7 and ai_generation=3 and updated_at='2026-01-02T00:00:00Z'",Integer.class)).isEqualTo(1003);
        assertThat(jdbc.queryForObject("select search_body from notes.note where note_id<>? limit 1",String.class,largeId))
                .isEqualTo("travel clientfactory https://example.test/path");
        assertThat(jdbc.queryForObject("select markdown from notes.note where note_id=?",String.class,largeId)).isEqualTo(large);
        assertThat(jdbc.queryForObject("""
                select count(*) from notes.note where note_id=? and revision=7 and ai_generation=3
                and created_at='2026-01-01T00:00:00Z' and updated_at='2026-01-02T00:00:00Z'
                and char_length(search_body)<=1024 and search_simple @@ plainto_tsquery('simple','prefixneedle')
                and not(search_simple @@ plainto_tsquery('simple','beyondprojectionneedle'))
                """,Integer.class,largeId)).isEqualTo(1);
        // Worst-case classes: dense distinct short words, overlapping hyphen/URL
        // tokens, NFC-expanding combining text, mixed CJK and four-byte code points.
        for(String source:java.util.List.of(large, "a-b-c-d-e-f-g-h ".repeat(100000),
                "https://a.test/b-c/d-e?q=f-g ".repeat(50000),
                "\u0344\u1100\u1161 ".repeat(300000), "東京🚀𐐀 ".repeat(150000))) {
            String bounded=jdbc.queryForObject("select notes.search_body_v1(?)",String.class,source);
            assertThat(bounded.codePointCount(0,bounded.length())).isLessThanOrEqualTo(1024);
            assertThat(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
            assertThat(bounded).isEqualTo(SearchTextProjection.of(source).text());
            for(String config:java.util.List.of("simple","english")) {
                assertThat(jdbc.queryForObject("""
                        select pg_column_size(setweight(to_tsvector(?::regconfig,left(lower(?),500)),'A')
                            || setweight(to_tsvector(?::regconfig,notes.search_body_v1(?)),'D'))
                        """,Integer.class,config,source,config,source)).isLessThan(65536);
            }
        }
        for(String source:java.util.List.of("## Cafe\u0301\n**東京** `ClientFactory` https://example.test/a-b", "\u1100\u1161 🚀", "  a\t b\n c  ",
                "<https://example.test/path> <person@example.test> <b>Text</b>")) {
            assertThat(jdbc.queryForObject("select lower(notes.search_plain_v1(?))",String.class,source))
                    .isEqualTo(SearchTextProjection.of(source).text());
        }
        // Runtime role can mutate current Notes while the trigger owns the projection.
        jdbc.execute("create role synthetic_search_runtime login password 'synthetic-runtime-password'");
        jdbc.execute("grant usage on schema notes to synthetic_search_runtime");
        jdbc.execute("grant select, update on notes.note to synthetic_search_runtime");
        var runtime=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),"synthetic_search_runtime","synthetic-runtime-password"));
        runtime.update("update notes.note set title='Replacementword' where owner_user_id=?",owner);
        assertThat(runtime.queryForObject("select count(*) from notes.note where search_simple @@ plainto_tsquery('simple','replacementword') and revision=7",Integer.class)).isEqualTo(1004);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    static String largeBody(String prefix) {
        var body=new StringBuilder(1_000_000).append(prefix).append(' ');
        // 142k distinct fixed-width lexemes stress vector storage, unlike repeated filler.
        for(int i=0;body.length()<999950;i++) body.append('z').append(String.format(java.util.Locale.ROOT,"%05x",i)).append(' ');
        body.setLength(999950);
        body.append(" beyondprojectionneedle ");
        while(body.length()<1_000_000) body.append(' ');
        return body.toString();
    }
}
