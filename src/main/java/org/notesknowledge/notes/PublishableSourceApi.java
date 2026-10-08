package org.notesknowledge.notes;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-only publication source operations. Anonymous consumers must never use this API. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PublishableSourceApi {
    public record Source(UUID noteId,long revision,String title,String markdown,List<String> tags) {
        public Source { tags=List.copyOf(tags); }
        @Override public String toString(){return "PublishableSource[REDACTED]";}
    }
    public record Status(boolean sourceExists,String lifecycle,boolean hasChanges,boolean updateReady) { }
    private final NotesRepository notes;
    private final NoteVersionRepository versions;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final IfMatchPrecondition preconditions;
    private final StrongCoreEtagCodec etags;
    PublishableSourceApi(NotesRepository notes,NoteVersionRepository versions,DatabaseUuidV7Generator ids,
        Clock clock,IfMatchPrecondition preconditions,StrongCoreEtagCodec etags) {
        this.notes=notes;this.versions=versions;this.ids=ids;this.clock=clock;this.preconditions=preconditions;this.etags=etags;
    }
    public Source preview(UUID owner,UUID note,String ifMatch) {
        var current=require(owner,note);
        preconditions.requireCurrent(ifMatch,etags.encode(new NoteCoreVersion(note,current.revision())));
        return source(current);
    }
    public Source requireCurrent(UUID owner,UUID note,long revision) {
        var current=require(owner,note);
        if(current.revision()!=revision)throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        return source(current);
    }
    public Source current(UUID owner,UUID note){return source(require(owner,note));}
    public UUID checkpointAndHold(UUID owner,Source expected,UUID publication) {
        var current=require(owner,expected.noteId());
        if(!source(current).equals(expected))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        // Reuse only this exact immutable revision, including a policy checkpoint made by Save.
        // The owning Note lock serializes checkpoint creation and hold acquisition.
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var version=versions.exactRevision(owner,current).orElseGet(()->{
            var created=ids.generate();versions.insert(owner,created,current,"publication",now);return created;
        });
        versions.acquirePublicationHold(owner,current.id(),version,publication,now);
        return version;
    }
    public void releaseHold(UUID owner,UUID note,UUID version,UUID publication) {
        versions.releasePublicationHold(owner,note,version,publication);
    }
    public Status sourceStatus(UUID owner,UUID note,long publishedRevision) {
        var current=notes.find(owner,note).orElse(null);
        return current==null?new Status(false,null,false,false):new Status(true,current.lifecycle(),
            current.revision()!=publishedRevision,"active".equals(current.lifecycle()));
    }
    private NoteRecord require(UUID owner,UUID note) {
        var current=notes.lock(owner,note).orElseThrow(()->ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        if(!"active".equals(current.lifecycle()))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        return current;
    }
    private static Source source(NoteRecord current){return new Source(current.id(),current.revision(),current.title(),current.markdown(),current.tags());}
}
