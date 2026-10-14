package org.notesknowledge.knowledge;

import java.util.function.LongSupplier;

/** One attempt, including source acquisition and all downstream embedding calls. Not a quota grant. */
final class DerivationAttemptBudget {
    static final long MAX_NANOS = 600_000_000_000L;
    static final long MAX_ENCODED_BYTES = 512L * 1024 * 1024;
    static final int MAX_MEDIA_CALLS = 100;
    static final int MAX_EMBEDDING_CALLS = 512;
    static final int MAX_CALLS = 640;
    private final LongSupplier ticker;
    private final long started;
    private long encodedBytes;
    private int mediaCalls;
    private int embeddingCalls;

    DerivationAttemptBudget() { this(System::nanoTime); }
    DerivationAttemptBudget(LongSupplier ticker) {
        this.ticker = java.util.Objects.requireNonNull(ticker);
        started = ticker.getAsLong();
    }
    void check() {
        if (ticker.getAsLong() - started >= MAX_NANOS) exhausted();
    }
    void providerRequest(boolean media, long estimatedEncodedBytes) {
        check();
        if (estimatedEncodedBytes <= 0 || estimatedEncodedBytes > MAX_ENCODED_BYTES - encodedBytes
                || (media ? ++mediaCalls > MAX_MEDIA_CALLS : ++embeddingCalls > MAX_EMBEDDING_CALLS)
                || mediaCalls + embeddingCalls > MAX_CALLS) exhausted();
        encodedBytes += estimatedEncodedBytes;
    }
    private static void exhausted() { throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED); }
    @Override public String toString() { return "DerivationAttemptBudget[bounded attempt]"; }
}
