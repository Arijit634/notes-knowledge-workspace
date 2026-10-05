CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Versioned, immutable presentation reduction. No HTML execution or URL resolution.
CREATE FUNCTION notes.search_plain_v1(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT btrim(regexp_replace(
        regexp_replace(
            regexp_replace(normalize(value, NFC), '</?[A-Za-z][A-Za-z0-9-]*([[:space:]][^>]*|/?)>', ' ', 'g'),
            '(^|\n)[ \t]*([#]{1,6}|[-*>])[ \t]+|[`*]|~~', ' ', 'g'),
        '[[:space:][:cntrl:]]+', ' ', 'g'));
$$;

ALTER TABLE notes.note
    ADD COLUMN search_title text,
    ADD COLUMN search_body text,
    ADD COLUMN search_text text,
    ADD COLUMN search_simple tsvector,
    ADD COLUMN search_english tsvector;

CREATE FUNCTION notes.refresh_search_v1() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_title := lower(notes.search_plain_v1(NEW.title));
    NEW.search_body := lower(notes.search_plain_v1(NEW.markdown));
    NEW.search_text := NEW.search_title || ' ' || NEW.search_body;
    NEW.search_simple := setweight(to_tsvector('simple', NEW.search_title), 'A')
        || setweight(to_tsvector('simple', NEW.search_body), 'D');
    NEW.search_english := setweight(to_tsvector('english', NEW.search_title), 'A')
        || setweight(to_tsvector('english', NEW.search_body), 'D');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_note_search_current
    BEFORE INSERT OR UPDATE OF title, markdown ON notes.note
    FOR EACH ROW EXECUTE FUNCTION notes.refresh_search_v1();

-- Bounded batches inside the migration transaction; no revision/updated_at changes.
DO $$
DECLARE changed integer;
BEGIN
    LOOP
        WITH batch AS (SELECT note_id FROM notes.note WHERE search_text IS NULL
                       ORDER BY note_id LIMIT 500)
        UPDATE notes.note n SET title = n.title FROM batch b WHERE n.note_id = b.note_id;
        GET DIAGNOSTICS changed = ROW_COUNT;
        EXIT WHEN changed = 0;
    END LOOP;
END;
$$;

ALTER TABLE notes.note
    ALTER COLUMN search_title SET NOT NULL,
    ALTER COLUMN search_body SET NOT NULL,
    ALTER COLUMN search_text SET NOT NULL,
    ALTER COLUMN search_simple SET NOT NULL,
    ALTER COLUMN search_english SET NOT NULL;

CREATE INDEX ix_note_search_simple ON notes.note USING gin(search_simple);
CREATE INDEX ix_note_search_english ON notes.note USING gin(search_english);
CREATE INDEX ix_note_search_trigram ON notes.note USING gin(search_text gin_trgm_ops);
CREATE INDEX ix_note_search_title_trigram ON notes.note USING gin(search_title gin_trgm_ops);
-- The existing owner/state B-tree can be intersected with these text indexes.
