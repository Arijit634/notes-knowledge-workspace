package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Frozen metadata/surrogate load corpus. No claim of OCR/transcription or Gemini semantic quality. */
final class FrozenScaleCorpus {
    static final String SEED="retrieval-scale-v2-20261010";
    static void notes(JdbcTemplate jdbc,UUID owner,int count) {
        jdbc.update("""
            insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at)
            select uuidv7(),?,case when i%2=0 then 'Synthetic deployment log ' else 'Synthetic travel log ' end||i,
                case when i%10=0 then repeat('# Synthetic deployment\n## Decision\nThe fictional release was postponed.\n## Earlier draft\nThe old plan said Tuesday; this is not the current decision.\n',30)
                    else case i%6 when 0 then '# Kyoto\nSynthetic lodging on the eastern shore; not the mountain observatory.'
                        when 1 then '# Release\nThe fictional deployment was delayed; the unrelated train arrived early.'
                        when 2 then '# Watch later\nFictional film: The Marigold Moon.'
                        when 3 then '# Earlier draft\nNear duplicate travel plan; fictional accommodation on the western shore.'
                        when 4 then '# Meeting\nSpeaker One described a postponed release; Speaker Two discussed a telescope.'
                        else '# Diagram\nSynthetic silver dome beside a red bicycle; unrelated caption.' end end,
                case when i%11=0 then 'archived' else 'active' end,true,1,1,clock_timestamp(),clock_timestamp()
            from generate_series(1,?) i
            """,owner,count);
    }
    static void mixed(JdbcTemplate jdbc,UUID owner,int total,AiDerivationProperties configuration,UUID policy) {
        populate(jdbc,owner,total,configuration,policy,true,null);
    }
    static void text(JdbcTemplate jdbc,UUID owner,int total,AiDerivationProperties configuration,UUID policy) {
        populate(jdbc,owner,total,configuration,policy,false,null);
    }
    static void text(JdbcTemplate jdbc,UUID owner,int total,AiDerivationProperties configuration,UUID policy,float[] vector) {
        if(vector.length!=configuration.dimension())throw new IllegalArgumentException("Synthetic vector width mismatch");
        populate(jdbc,owner,total,configuration,policy,false,vector);
    }
    private static void populate(JdbcTemplate jdbc,UUID owner,int total,AiDerivationProperties configuration,UUID policy,boolean mixed,float[] vector) {
        int perMedia=mixed?total/10:0;
        notes(jdbc,owner,total-4*perMedia);
        // Scale repeats the frozen everyday corpus, not 10,000 independently judged real-model Notes.
        // Keep notes() itself unchanged: its separate exhaustive-URL tests deliberately have two occurrences.
        String corpus=new tools.jackson.databind.ObjectMapper().writeValueAsString(FrozenQualityCorpus.notes().stream()
            .filter(FrozenQualityCorpus.Note::aiEnabled).toList());
        jdbc.update("""
            with corpus as materialized(select ?::jsonb value),
            numbered as(select note_id,row_number() over(order by note_id)-1 position
                from notes.note where owner_user_id=?)
            update notes.note n set title=(c.value->((f.position%80)::int)->>'title')||' / copy '||f.position,
                markdown=c.value->((f.position%80)::int)->>'body'
            from numbered f cross join corpus c where n.note_id=f.note_id
            """,corpus,owner);
        for(String modality:List.of("image","audio","video","pdf"))jdbc.update("""
            insert into notes.attachment(attachment_id,note_id,owner_user_id,media_kind,object_reference,display_filename,media_type,
                size_bytes,width,height,duration_seconds,page_count,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
            select uuidv7(),note_id,owner_user_id,?,
                'private-attachment/'||encode(sha256(uuidv7()::text::bytea),'hex'),'synthetic-load',?,100,?,?,?,?,'stored','accepted','retained',1,1,clock_timestamp(),clock_timestamp()
            from notes.note where owner_user_id=? order by note_id limit ?
            """,modality,switch(modality){case "image"->"image/png";case "audio"->"audio/wav";case "video"->"video/mp4";default->"application/pdf";},
            modality.equals("image")||modality.equals("video")?32:null,modality.equals("image")||modality.equals("video")?32:null,
            modality.equals("audio")||modality.equals("video")?10.0:null,modality.equals("pdf")?20:null,owner,perMedia);
        for(String modality:List.of("note","image","audio","video","pdf")) {
            var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),modality);
            String from=modality.equals("note")?"select note_id,null::uuid attachment_id,owner_user_id from notes.note where owner_user_id=?"
                :"select note_id,attachment_id,owner_user_id from notes.attachment where owner_user_id=? and media_kind='"+modality+"'";
            jdbc.update("""
                insert into knowledge.private_derived_representation(owner_user_id,source_kind,source_note_id,source_attachment_id,source_revision,
                    processing_generation,attachment_generation,derivation_class,processing_policy_id,lineage_id,lineage_configuration,modality,embedding_dimension,distance_operator)
                select owner_user_id,?,note_id,attachment_id,1,1,?,'text_surrogate',?,?,?,?,?,'cosine' from (
                """+from+") fixtures",modality.equals("note")?"note":"attachment",modality.equals("note")?null:1,policy,l.id(),l.configuration(),modality,configuration.dimension(),owner);
        }
        jdbc.update("""
            insert into knowledge.private_derived_segment(parent_id,owner_user_id,ordinal,surrogate_text,segment_kind,heading_ancestry,
                source_start,source_end,page_number,time_start,time_end,lineage_id,embedding_dimension,embedding)
            select r.derived_representation_id,r.owner_user_id,0,
                case modality when 'note' then left(n.markdown,12000)
                    when 'image' then 'Controlled description: telescope diagram with a silver dome and unrelated caption'
                    when 'audio' then 'Controlled transcript: Speaker One discusses a postponed release; Speaker Two mentions an unrelated train'
                    when 'video' then 'Controlled sampled scene: a red bicycle beside a silver dome; only seconds two through four are represented'
                    else 'Controlled page seventeen surrogate: a scanned deployment diagram with visible labels; not proof of actual OCR' end,
                case modality when 'note' then 'note_text' when 'pdf' then 'pdf_text' when 'image' then 'whole_image' when 'audio' then 'transcript' else 'video_scene' end,'',
                case when modality='note' then 0 end,case when modality='note' then least(length(n.markdown),12000) end,case when modality='pdf' then 17 end,
                case when modality in ('audio','video') then 2 end,case when modality in ('audio','video') then 4 end,
                lineage_id,?,?::vector
            from knowledge.private_derived_representation r join notes.note n on n.note_id=r.source_note_id and n.owner_user_id=r.owner_user_id
            where r.owner_user_id=? and r.state='ready'
            """,configuration.dimension(),vector==null?basisVector(configuration.dimension()):wireVector(vector),owner);
        if(mixed) {
            jdbc.update("""
                insert into notes.note_tag(note_id,owner_user_id,normalized_label,display_label,created_at)
                select note_id,owner_user_id,'synthetic-travel','Synthetic travel',clock_timestamp()
                from (select *,row_number() over(order by note_id) position from notes.note where owner_user_id=?) n
                where position%7=0
                """,owner);
            // Retain stale roots deliberately. Currentness, not fixture cleanup, must exclude them before scoring.
            jdbc.update("""
                with numbered as (select note_id,row_number() over(order by note_id) position from notes.note where owner_user_id=?)
                update notes.note n set ai_enabled=false,ai_generation=ai_generation+1,revision=revision+1
                from numbered f where n.note_id=f.note_id and f.position%37=0
                """,owner);
            jdbc.update("""
                with numbered as (select note_id,row_number() over(order by note_id) position from notes.note where owner_user_id=?)
                update notes.note n set pre_trash_state=lifecycle_state,lifecycle_state='trashed',trashed_at=clock_timestamp(),revision=revision+1
                from numbered f where n.note_id=f.note_id and f.position%41=0
                """,owner);
            jdbc.update("""
                with numbered as (select note_id,row_number() over(order by note_id) position from notes.note where owner_user_id=?)
                update notes.note n set ai_generation=ai_generation+1,revision=revision+1
                from numbered f where n.note_id=f.note_id and f.position%43=0
                """,owner);
        }
    }
    private static String basisVector(int dimension) {
        return "["+"0,".repeat(dimension-1)+"1]";
    }
    private static String wireVector(float[] vector) {
        return "["+java.util.stream.IntStream.range(0,vector.length).mapToObj(i->Float.toString(vector[i])).collect(java.util.stream.Collectors.joining(","))+"]";
    }
    private FrozenScaleCorpus(){ }
}
