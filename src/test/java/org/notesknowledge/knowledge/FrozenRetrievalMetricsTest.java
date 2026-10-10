package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("EVALUATION")
class FrozenRetrievalMetricsTest {
    @Test void imperfectRankingHasKnownRecallReciprocalRankAndDiscountedGain() {
        var m=FrozenRetrievalMetrics.score(List.of("distractor","b","a"),Map.of("a",2,"b",1));
        assertThat(m.recall1()).isZero();assertThat(m.recall5()).isEqualTo(1);
        assertThat(m.recall10()).isEqualTo(1);assertThat(m.recall20()).isEqualTo(1);assertThat(m.mrr()).isEqualTo(.5);
        assertThat(m.ndcg10()).isCloseTo((1/(Math.log(3)/Math.log(2))+1.5)/(3+1/(Math.log(3)/Math.log(2))),within(1e-12));
    }
    @Test void missesAreNotDroppedFromTheDenominator() {
        var m=FrozenRetrievalMetrics.score(List.of("a"),Map.of("a",1,"missing",1));
        assertThat(m.recall20()).isEqualTo(.5);assertThat(m.ndcg10()).isLessThan(1);
    }
    @Test void duplicateRankingsCannotInflateRecall() {
        assertThatThrownBy(()->FrozenRetrievalMetrics.score(List.of("a","a"),Map.of("a",1))).isInstanceOf(IllegalArgumentException.class);
    }
}
