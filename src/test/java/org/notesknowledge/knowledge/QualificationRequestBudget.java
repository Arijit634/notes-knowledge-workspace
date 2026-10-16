package org.notesknowledge.knowledge;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** Test-only total budget spanning media, document/query embedding and generation.
 * Wait BEFORE acquiring coordination/issuing fresh permits, never inside a provider adapter.
 * New directory only: interrupted/failed execution requires explicit operator review, not replay. */
final class QualificationRequestBudget {
    record Limits(int requests,long estimatedBytes,long outputTokens,int intervalSeconds) {
        void require(){if(requests<1||requests>64||estimatedBytes<1||estimatedBytes>64L*1024*1024
            ||outputTokens<8192||outputTokens>20L*8192||intervalSeconds<15)throw denied();}
    }
    private final Path directory;private final Limits limits;private final Clock clock;
    private int requests;private long bytes,tokens;private Instant last;private boolean failed;
    QualificationRequestBudget(Path directory,Limits limits,Clock clock)throws IOException {
        limits.require();this.directory=directory;this.limits=limits;this.clock=clock;
        Files.createDirectory(directory);
        QualificationJournal.forceCreate(directory.resolve("limits.json"),new ObjectMapper().writeValueAsBytes(limits));
    }
    synchronized Duration delay(){var value=last==null?Duration.ZERO:Duration.between(clock.instant(),last.plusSeconds(limits.intervalSeconds()));return value.isNegative()?Duration.ZERO:value;}
    synchronized <T>T call(String kind,long estimate,long output,Supplier<T> action) {
        if(failed||!Set.of("media","document-embedding","query-embedding","generation").contains(kind)
                ||estimate<1||output<0||requests>=limits.requests()||estimate>limits.estimatedBytes()-bytes
                ||output>limits.outputTokens()-tokens||!delay().isZero())throw denied();
        requests++;bytes+=estimate;tokens+=output;last=clock.instant();
        String name="%06d".formatted(requests);long started=System.nanoTime();
        try {
            QualificationJournal.forceCreate(directory.resolve(name+"-reserved.json"),new ObjectMapper().writeValueAsBytes(Map.of(
                "kind",kind,"estimatedRequestBytes",estimate,"reservedOutputTokens",output,"startedUtc",last.toString())));
            T result=action.get();
            QualificationJournal.forceCreate(directory.resolve(name+"-completed.json"),new ObjectMapper().writeValueAsBytes(Map.of(
                "kind",kind,"elapsedMillis",(System.nanoTime()-started)/1000000)));
            return result;
        } catch(IOException failure){failed=true;throw new IllegalStateException("Qualification budget journal unavailable");}
        catch(RuntimeException|Error failure){failed=true;throw failure;}
    }
    Map<String,Object> totals(){return Map.of("requests",requests,"estimatedRequestBytes",bytes,"reservedOutputTokens",tokens,
        "actualProviderTokens","not exposed by ports","actualSerializedRequestBytes","controlled wire tests only; live estimate","failed",failed);}
    private static IllegalStateException denied(){return new IllegalStateException("Qualification request budget denied");}
}
