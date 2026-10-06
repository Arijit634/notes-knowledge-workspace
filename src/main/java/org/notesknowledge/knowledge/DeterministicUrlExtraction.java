package org.notesknowledge.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.notesknowledge.knowledge.spi.PrivateKnowledgeSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded Note-only strategy, not an HTTP API, provider permit or durable operation. */
@Service
public class DeterministicUrlExtraction {
    public enum Coverage { COMPLETE, CHANGED, TRUNCATED }
    public enum Field { TITLE, MARKDOWN }
    public record Occurrence(UUID noteId,long revision,Field field,int offset,int length,String display) {
        @Override public String toString() { return "UrlOccurrence[REDACTED]"; }
    }
    public record Item(String value,List<Occurrence> occurrences) {
        public Item { occurrences=List.copyOf(occurrences); }
        @Override public String toString() { return "ExtractedUrl[REDACTED]"; }
    }
    public record Outcome(List<Item> items,Coverage coverage,PrivateKnowledgeSource.Boundary boundary,
            PrivateKnowledgeSource.Fingerprint fingerprint,long inspectedSources,long recognizedOccurrences,
            boolean resultsTruncated,boolean provenanceTruncated) {
        public Outcome { items=List.copyOf(items); }
        @Override public String toString() { return "DeterministicUrlOutcome[REDACTED]"; }
    }
    private final PrivateKnowledgeSource sources;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final DeterministicExtractionProperties bounds;
    DeterministicUrlExtraction(PrivateKnowledgeSource sources,ObjectProvider<PlatformTransactionManager> managers,
            DeterministicExtractionProperties bounds) { this.sources=sources;this.managers=managers;this.bounds=bounds; }

    public Outcome extract(PrivateKnowledgeSource.Scope scope) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Corpus traversal must not inherit a long transaction");
        var boundary=shortTransaction(()->sources.capture(scope));
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        var first=new Fingerprinter(); var reducer=new Reducer();
        UUID after=null;
        do {
            if(System.nanoTime()>=deadline) return new Outcome(List.of(),Coverage.TRUNCATED,boundary,first.value(),first.count,reducer.count,true,reducer.provenanceTruncated);
            UUID position=after;
            var page=shortTransaction(()->sources.readCurrent(boundary,position,PrivateKnowledgeSource.Purpose.DETERMINISTIC_URL_EXTRACTION));
            for(var note:page.items()) {
                if(first.count==PrivateKnowledgeSource.MAX_SOURCES)
                    return new Outcome(List.of(),Coverage.TRUNCATED,boundary,first.value(),first.count,reducer.count,true,reducer.provenanceTruncated);
                first.add(note.metadata());
                reducer.add(note.metadata(),Field.TITLE,note.title());
                reducer.add(note.metadata(),Field.MARKDOWN,note.markdown());
            }
            after=page.continuation();
        } while(after!=null);
        var second=new Fingerprinter(); after=null;
        do {
            if(System.nanoTime()>=deadline) return new Outcome(List.of(),Coverage.TRUNCATED,boundary,first.value(),first.count,reducer.count,true,reducer.provenanceTruncated);
            UUID position=after;
            var page=shortTransaction(()->sources.readMetadata(boundary,position));
            for(var metadata:page.items()) {
                second.add(metadata);
                if(second.count>PrivateKnowledgeSource.MAX_SOURCES) break;
            }
            after=page.continuation();
        } while(after!=null && second.count<=PrivateKnowledgeSource.MAX_SOURCES);
        var terminal=shortTransaction(()->sources.currentFingerprint(boundary));
        if(!first.value().equals(second.value()) || !second.value().equals(terminal)) {
            // Never return source content/provenance whose current eligibility cannot be established.
            return new Outcome(List.of(),Coverage.CHANGED,boundary,terminal,first.count,reducer.count,
                    reducer.resultsTruncated,reducer.provenanceTruncated);
        }
        boolean truncated=reducer.resultsTruncated||reducer.provenanceTruncated;
        return new Outcome(reducer.items(),truncated?Coverage.TRUNCATED:Coverage.COMPLETE,boundary,terminal,
                first.count,reducer.count,reducer.resultsTruncated,reducer.provenanceTruncated);
    }
    private <T> T shortTransaction(Supplier<T> work) {
        var transaction=new TransactionTemplate(managers.getObject());
        transaction.setTimeout(3);
        return transaction.execute(status->work.get());
    }
    private final class Reducer {
        final LinkedHashMap<String,ArrayList<Occurrence>> values=new LinkedHashMap<>();
        long count;int retained;boolean resultsTruncated,provenanceTruncated;
        void add(PrivateKnowledgeSource.Metadata source,Field field,String text) {
            boolean unsupported=new UrlRecognizer().scan(text,match->{
                count++;
                if(retained==bounds.maximumOccurrences()) { provenanceTruncated=true;return; }
                var occurrences=values.get(match.comparisonKey());
                if(occurrences==null) {
                    if(values.size()==bounds.maximumResults()) { resultsTruncated=true;return; }
                    occurrences=new ArrayList<>();values.put(match.comparisonKey(),occurrences);
                }
                occurrences.add(new Occurrence(source.noteId(),source.revision(),field,match.offset(),match.length(),match.display()));retained++;
            });
            resultsTruncated|=unsupported;
        }
        List<Item> items() { return values.entrySet().stream().map(e->new Item(e.getKey(),e.getValue())).toList(); }
    }
    static final class Fingerprinter {
        long count;final long[] words=new long[4];
        void add(PrivateKnowledgeSource.Metadata source) {
            try {
                var bytes=MessageDigest.getInstance("SHA-256").digest(source.consistencyText().getBytes(StandardCharsets.UTF_8));
                var buffer=ByteBuffer.wrap(bytes);
                for(int i=0;i<4;i++) words[i]^=buffer.getLong();
                count++;
            } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        PrivateKnowledgeSource.Fingerprint value() { return new PrivateKnowledgeSource.Fingerprint(count,List.of(words[0],words[1],words[2],words[3])); }
    }
}
