package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("EVALUATION")
class FrozenQualityCorpusTest {
    @Test void corpusHasIndependentFrozenJudgmentsAndBalancedSplits() {
        var notes=FrozenQualityCorpus.notes();var queries=FrozenQualityCorpus.queries();
        assertThat(notes).hasSize(81);assertThat(notes.stream().filter(FrozenQualityCorpus.Note::aiEnabled)).hasSize(80);
        assertThat(notes.stream().map(FrozenQualityCorpus.Note::id)).doesNotHaveDuplicates();
        assertThat(notes.stream().map(FrozenQualityCorpus.Note::body)).doesNotHaveDuplicates();
        assertThat(queries).hasSize(48);assertThat(queries.stream().map(FrozenQualityCorpus.Query::id)).doesNotHaveDuplicates();
        for(String split:List.of("development","held-out"))assertThat(queries.stream().filter(q->q.split().equals(split))).hasSize(24);
        var ids=notes.stream().map(FrozenQualityCorpus.Note::id).toList();
        for(var query:queries){assertThat(ids).containsAll(query.gold().keySet());assertThat(query.expected()).isNotBlank();}
        assertThat(queries.stream().filter(q->q.gold().isEmpty())).hasSize(2);
        assertThat(notes.stream().filter(n->!n.aiEnabled())).extracting(FrozenQualityCorpus.Note::id).containsExactly("n81");
        assertThat(notes.stream().filter(n->n.id().equals("n76")).findFirst().orElseThrow().body()).hasSizeGreaterThan(7000).endsWith("end of the inventory.");
        assertThat(FrozenQualityCorpus.notes()).isEqualTo(notes);assertThat(FrozenQualityCorpus.queries()).isEqualTo(queries);
    }
    @Test void metricsUseAllRelevantSourcesAndGradedRankingRatherThanAnyHit() {
        var judgments=Map.of("a",2,"b",1);
        var partial=FrozenRetrievalMetrics.score(List.of("x","a"),judgments);
        assertThat(partial.recall1()).isZero();assertThat(partial.recall5()).isEqualTo(.5);
        assertThat(partial.mrr()).isEqualTo(.5);assertThat(partial.ndcg10()).isBetween(0.0,1.0);
        assertThat(FrozenRetrievalMetrics.score(List.of("a","b"),judgments).ndcg10()).isEqualTo(1);
        assertThatThrownBy(()->FrozenRetrievalMetrics.score(List.of("a","a"),judgments)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FrozenRetrievalMetrics.score(List.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void livePlanningCountsChunksRatherThanAssumingOneRequestPerNote() {
        int chunks=FrozenQualityCorpus.notes().stream().filter(FrozenQualityCorpus.Note::aiEnabled)
            .mapToInt(n->new MarkdownChunker().chunk(n.body()).size()).sum();
        assertThat(chunks).isGreaterThan(80);
        // Forty query requests plus eighty roots leave no budget for media or extra chunks.
        assertThat(chunks+40).isGreaterThan(120);
        assertThat(chunks+28+4).isLessThanOrEqualTo(120); // four one-segment media requests are an estimate, not a promise
    }
}
