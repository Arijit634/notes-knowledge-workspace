package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("EVALUATION")
class MarkdownChunkerTest {
    @Test void offsetsHeadingsAndSemanticBoundariesAreDeterministic() {
        String source="# Heading\n\nParagraph with https://example.test/path.\n\n## Child\n- one\n- two\n";
        var first=new MarkdownChunker().chunk(source);
        assertThat(first).isEqualTo(new MarkdownChunker().chunk(source));
        assertThat(first).hasSize(2);
        assertThat(first.getLast().heading()).isEqualTo("Heading / Child");
        for(var s:first)assertThat(source.substring(s.start(),s.end())).isEqualTo(s.text());
    }
    @Test void unicodeAndOversizedCodeRemainBoundedWithTruthfulOffsets() {
        String source="```java\n"+"longIdentifier ".repeat(400)+"\n```\n"+"東京🚀 ".repeat(2000);
        var segments=new MarkdownChunker().chunk(source);
        for(var segment:segments) {
            assertThat(MarkdownChunker.estimatedUnits(segment.text())).isLessThanOrEqualTo(MarkdownChunker.HARD);
            assertThat(source.substring(segment.start(),segment.end())).isEqualTo(segment.text());
        }
    }
    @Test void fullSourceCapAndSegmentBudgetFailWithoutPartialResult() {
        assertThatThrownBy(()->new MarkdownChunker().chunk("x".repeat(1_000_001))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new MarkdownChunker().chunk("# short\nbody\n".repeat(513))).isInstanceOf(DerivationFailure.class);
    }
    @Test void emptyNoteHasNoInventedContent() {assertThat(new MarkdownChunker().chunk("  \n")).isEmpty();}
    @Test void latinParagraphsUseTokenApproximationRatherThanCharacterSizedChunks() {
        var chunks=new MarkdownChunker().chunk(("Ordinary synthetic paragraph words. ".repeat(72)+"\n\n").repeat(8));
        assertThat(chunks).hasSize(8);
        assertThat(chunks).allSatisfy(s->assertThat(MarkdownChunker.estimatedUnits(s.text())).isBetween(400,800));
    }
    @Test void invalidLocationsAndSurrogatesFail() {
        assertThatThrownBy(()->new DerivedSegment("x","transcript","",null,null,null,Double.NaN,2.0,null,null,null,null)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new DerivedSegment("x","image_region","",null,null,null,null,null,0.9,0.0,0.5,0.5)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->DerivedSegment.note("x".repeat(12001),"",0,12001)).isInstanceOf(DerivationFailure.class);
    }
}
