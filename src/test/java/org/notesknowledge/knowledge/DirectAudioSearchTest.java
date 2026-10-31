package org.notesknowledge.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class DirectAudioSearchTest {
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
