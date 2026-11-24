package org.notesknowledge.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class DirectAudioSearchTest {
    private static DerivationFailure media503(){return failure(503,ProviderFailureDiagnostic.Stage.MEDIA,KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);}
    private static DerivationFailure failure(int status,ProviderFailureDiagnostic.Stage stage,KnowledgeWork.Failure category){
        return new DerivationFailure(category,new ProviderFailureDiagnostic(stage,ProviderFailureDiagnostic.Kind.HTTP,status,
            ProviderFailureDiagnostic.Elapsed.UNDER_ONE_SECOND,ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED,false));
    }
    @Test void oneRecoverySharesTheOriginalSixCallBudgetAndCannotBeReservedTwice(){
        var clock=new java.util.concurrent.atomic.AtomicLong();var budget=new DirectAudioSearch.Budget(clock::get);
        assertThat(budget.reserve503Recovery(media503())).isFalse();
        budget.next();assertThat(budget.reserve503Recovery(media503())).isTrue();
        assertThat(budget.reserve503Recovery(media503())).isFalse();budget.recoveryNext();
        for(int i=0;i<4;i++)budget.next();
        assertThatThrownBy(budget::next).isInstanceOf(DerivationFailure.class);
        assertThat(budget.reserve503Recovery(media503())).isFalse();
    }
    @Test void recoveryReservesTheUnchangedMaximumInFlightDeadlineAfterWaitingAndReauthorization(){
        var clock=new java.util.concurrent.atomic.AtomicLong();var budget=new DirectAudioSearch.Budget(clock::get);budget.next();
        clock.set(28_000_000_000L);assertThat(budget.reserve503Recovery(media503())).isTrue();
        clock.set(31_000_000_000L);assertThatThrownBy(budget::recoveryNext).isInstanceOf(DerivationFailure.class);
        var late=new DirectAudioSearch.Budget(clock::get);late.next();clock.addAndGet(29_000_000_000L);
        assertThat(late.reserve503Recovery(media503())).isFalse();
        clock.addAndGet(61_000_000_000L);assertThatThrownBy(late::check).isInstanceOf(DerivationFailure.class);
    }
    @Test void onlyConfirmedMedia503MayRecoverAndNeverEmbeddingPolicyOrOutputFailures(){
        for(int status:new int[]{400,401,403,404,408,429,500,502,504}){
            var budget=new DirectAudioSearch.Budget(()->0L);budget.next();
            assertThat(budget.reserve503Recovery(failure(status,ProviderFailureDiagnostic.Stage.MEDIA,KnowledgeWork.Failure.TRANSIENT_DEPENDENCY))).isFalse();
        }
        for(var stage:java.util.List.of(ProviderFailureDiagnostic.Stage.EMBEDDING,ProviderFailureDiagnostic.Stage.GENERATION)){
            var budget=new DirectAudioSearch.Budget(()->0L);budget.next();assertThat(budget.reserve503Recovery(failure(503,stage,KnowledgeWork.Failure.TRANSIENT_DEPENDENCY))).isFalse();
        }
        for(var category:java.util.List.of(KnowledgeWork.Failure.INVALID_SOURCE,KnowledgeWork.Failure.POLICY_BLOCKED,KnowledgeWork.Failure.INVALID_OUTPUT,KnowledgeWork.Failure.QUOTA)){
            var budget=new DirectAudioSearch.Budget(()->0L);budget.next();assertThat(budget.reserve503Recovery(failure(503,ProviderFailureDiagnostic.Stage.MEDIA,category))).isFalse();
        }
        var budget=new DirectAudioSearch.Budget(()->0L);budget.next();assertThat(budget.reserve503Recovery(new DerivationFailure(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY))).isFalse();
    }
    @Test void interruptedBackoffRestoresInterruptionAndDoesNotAttemptRecovery(){
        var search=new DirectAudioSearch(null,null,null,null,null,null,null,null);
        try{Thread.currentThread().interrupt();assertThatThrownBy(search::waitForVerificationRecovery).isInstanceOf(DerivationFailure.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();}
        finally{Thread.interrupted();}
    }
    @Test void audioRoutingUsesGenericIntentWithoutAnApprovedQuestionList() {
        var configuration=mock(AiDerivationProperties.class);when(configuration.directAudioSearch()).thenReturn(true);
        var search=new DirectAudioSearch(configuration,null,null,null,null,null,null,null);
        assertThat(search.applies("Find a saved audio with some completely new sound")).isTrue();
        assertThat(search.applies("Where is the beat I saved yesterday?")).isTrue();
        assertThat(search.applies("আমার বাংলা গান")).isTrue();
        assertThat(search.applies("Which footballers did I save?")).isFalse();
        when(configuration.directAudioSearch()).thenReturn(false);assertThat(search.applies("find audio")).isFalse();
        when(configuration.directAudioSearch()).thenReturn(true);
        assertThat(search.excludesOnFailure("audio","find an audio clip with a beat")).isTrue();
        assertThat(search.excludesOnFailure("audio","find meme audio with a beat")).isTrue();
    }
    @Test void nativeSimilarityRejectsInvalidVectorsAndIsNotAConfidenceValue() {
        assertThat(DirectAudioSearch.cosine(new float[]{1,0},new float[]{0,1})).isZero();
        for(float[] invalid:new float[][]{new float[]{0,0},new float[]{Float.NaN,0},new float[]{Float.POSITIVE_INFINITY,0},new float[]{1}})
            assertThatThrownBy(()->DirectAudioSearch.cosine(new float[]{1,0},invalid)).isInstanceOf(DerivationFailure.class);
    }
    @Test void explicitVideoRequestCannotBeSatisfiedByStandaloneAudio() {
        var configuration=mock(AiDerivationProperties.class);when(configuration.directAudioSearch()).thenReturn(true);when(configuration.directVideoSoundtrackSearch()).thenReturn(true);
        var search=new DirectAudioSearch(configuration,null,null,null,null,null,null,null);
        assertThat(search.applies("find a video with background noises")).isTrue();
        assertThat(search.excludesOnFailure("audio","find a video with background noises")).isTrue();
        assertThat(search.excludesOnFailure("video","find a video with background noises")).isTrue();
        assertThat(search.excludesOnFailure("note","find a video with background noises")).isFalse();
    }
}
