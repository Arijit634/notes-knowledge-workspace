package org.notesknowledge.knowledge;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class MediaContentEvidenceTest {
    @ParameterizedTest @ValueSource(strings={"https://video.example.test/opaque?id=42","youtu.be/opaque123?si=synthetic","<https://example.test/opaque>","Note title: https://example.test/opaque"})
    void opaqueLocatorsCannotBecomeVideoContentEvidence(String text) {
        var item=new QueryEvidenceRepository.Evidence(null,null,null,"synthetic","note",0,DerivedSegment.note(text,"",0,text.length()));
        var engine=new KnowledgeQueryEngine(null,null,null,null,null,null,null,null);
        var result=engine.generate(null,"Find the meme video with a fictional detective","answer",List.of(item),()->true).result();
        assertThat(result.insufficientEvidence()).isTrue();assertThat(result.aiAnswer()).isNull();assertThat(result.citations()).isEmpty();
    }
    @Test void realMediaAndExplicitAnnotationsRemainEligible() {
        assertThat(KnowledgeQueryEngine.supportsMediaContent("find the elephant video","video","An elephant walks across the road.")).isTrue();
        assertThat(KnowledgeQueryEngine.supportsMediaContent("find the elephant video","note","Elephant clip I saved: https://example.test/opaque")).isTrue();
        assertThat(KnowledgeQueryEngine.supportsMediaContent("find the elephant video","note","[Elephant clip](https://example.test/opaque)")).isTrue();
    }
    @Test void explicitLinkLookupAndNonMediaFactsAreNotSuppressed() {
        for(String query:List.of("What video link did I save?","list all URLs","find the website address","what was my account username"))
            assertThat(KnowledgeQueryEngine.supportsMediaContent(query,"note","https://example.test/opaque")).isTrue();
    }
    @Test void filteringCannotRelaxTheExistingInputBudget() {
        String text="https://example.test/opaque";
        var item=new QueryEvidenceRepository.Evidence(null,null,null,"synthetic","note",0,DerivedSegment.note(text,"",0,text.length()));
        var engine=new KnowledgeQueryEngine(null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->engine.generate(null,"find the video","answer",java.util.Collections.nCopies(KnowledgeQueryLimits.evidence("answer")+1,item),()->true))
            .isInstanceOfSatisfying(DerivationFailure.class,e->assertThat(e.category).isEqualTo(KnowledgeWork.Failure.BUDGET_EXCEEDED));
    }

}
