package org.notesknowledge.knowledge;

import java.util.*;
import java.util.function.*;

/** Test/local evaluation only, never application authority. A single pinned media failure
 * is preserved while approved query-only embeddings continue under fresh production gates. */
final class QualificationTextEvaluation {
    private final QualificationJournal journal;
    private final QualificationJournal.Event failedMedia;
    private final Set<String> approvedQueries;
    private final boolean cacheOnly;

    QualificationTextEvaluation(QualificationJournal journal, QualificationJournal.Event failedMedia,
            Set<String> approvedQueries, boolean cacheOnly) {
        this.journal=journal;this.failedMedia=failedMedia;this.approvedQueries=Set.copyOf(approvedQueries);this.cacheOnly=cacheOnly;
        if(!failedMedia.source().equals("media-image")||!failedMedia.kind().equals("generation")||!failedMedia.state().equals("reserved"))
            throw new IllegalArgumentException("Not the documented media failure");
        validateReservations();
    }

    float[] query(String query, String key, String label, Runnable freshAuthorization,
            Consumer<float[]> validateVector, Supplier<float[]> dispatch) throws java.io.IOException {
        if(!approvedQueries.contains(ProviderDispatchPolicy.queryFingerprint(query)))
            throw new IllegalStateException("Query is not predeclared and approved");
        validateReservations();freshAuthorization.run();
        float[] cached=journal.completed(key,float[].class);
        if(cached!=null){validateVector.accept(cached);return cached;}
        if(cacheOnly)throw new IllegalStateException("Offline preflight cannot dispatch");
        var reservation=journal.reserve(key,label,"embedding");
        // Durable reservation precedes the external effect; a failure remains charged/uncertain.
        freshAuthorization.run();float[] result=dispatch.get();validateVector.accept(result);
        journal.complete(reservation,result);return result;
    }

    void validateReservations() {
        if(!journal.uncertainEvents().equals(List.of(failedMedia)))
            throw new IllegalStateException("Unexpected uncertain request; no further dispatch");
    }

    static void rejectSourceOrGenerationDispatch(){throw new IllegalStateException("Text evaluation prohibits source/media/generation dispatch");}
}
