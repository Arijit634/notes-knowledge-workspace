package org.notesknowledge.knowledge;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST")
class DerivationAttemptBudgetTest {
    @Test void elapsedBudgetIncludesTimeBeforeTheFirstProviderCall() {
        var clock=new AtomicLong(500);
        var budget=new DerivationAttemptBudget(clock::get);
        clock.addAndGet(DerivationAttemptBudget.MAX_NANOS);
        exhausted(()->budget.providerRequest(true,1));
    }
    @Test void timeImmediatelyBeforeTheDeadlineIsAllowed() {
        var clock=new AtomicLong();var budget=new DerivationAttemptBudget(clock::get);
        clock.set(DerivationAttemptBudget.MAX_NANOS-1);
        budget.providerRequest(false,1);
        clock.incrementAndGet();exhausted(budget::check);
    }
    @Test void checksAfterInFlightWorkVetoLateCompletion() {
        var clock=new AtomicLong();var budget=new DerivationAttemptBudget(clock::get);
        budget.providerRequest(true,1);
        clock.set(DerivationAttemptBudget.MAX_NANOS+1);
        exhausted(budget::check);
    }
    @Test void exactEncodedLimitIsAllowedButOneAdditionalByteIsNot() {
        var budget=new DerivationAttemptBudget(()->0);
        budget.providerRequest(true,DerivationAttemptBudget.MAX_ENCODED_BYTES);
        exhausted(()->budget.providerRequest(false,1));
    }
    @Test void mediaAndEmbeddingShareOneEncodedBudget() {
        var budget=new DerivationAttemptBudget(()->0);
        budget.providerRequest(true,DerivationAttemptBudget.MAX_ENCODED_BYTES-10);
        budget.providerRequest(false,10);
        exhausted(()->budget.providerRequest(false,1));
    }
    @Test void hugeSizesCannotOverflowIntoAnAllowedRequest() {
        var budget=new DerivationAttemptBudget(()->0);
        exhausted(()->budget.providerRequest(true,Long.MAX_VALUE));
    }
    @Test void zeroAndNegativeSizesFailClosed() {
        for(long size:new long[]{0,-1,Long.MIN_VALUE}) {
            var budget=new DerivationAttemptBudget(()->0);
            exhausted(()->budget.providerRequest(false,size));
        }
    }
    @Test void mediaCallsHaveAnIndependentBound() {
        var budget=new DerivationAttemptBudget(()->0);
        for(int i=0;i<DerivationAttemptBudget.MAX_MEDIA_CALLS;i++)budget.providerRequest(true,1);
        exhausted(()->budget.providerRequest(true,1));
    }
    @Test void downstreamEmbeddingCallsHaveAnIndependentBound() {
        var budget=new DerivationAttemptBudget(()->0);
        for(int i=0;i<DerivationAttemptBudget.MAX_EMBEDDING_CALLS;i++)budget.providerRequest(false,1);
        exhausted(()->budget.providerRequest(false,1));
    }
    @Test void monotonicTickerWrapDoesNotInvalidateAnOrdinaryInterval() {
        var clock=new AtomicLong(Long.MAX_VALUE-10);var budget=new DerivationAttemptBudget(clock::get);
        clock.addAndGet(20);budget.check();
    }
    @Test void diagnosticsContainNeitherSourceNorPayloadState() {
        var budget=new DerivationAttemptBudget(()->0);
        budget.providerRequest(true,12345);
        assertThat(budget.toString()).isEqualTo("DerivationAttemptBudget[bounded attempt]");
    }
    private static void exhausted(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DerivationFailure.class,
            failure->assertThat(failure.category).isEqualTo(KnowledgeWork.Failure.BUDGET_EXCEEDED));
    }
}
