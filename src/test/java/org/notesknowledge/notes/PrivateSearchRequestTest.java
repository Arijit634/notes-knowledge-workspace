package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("RETRIEVAL")
class PrivateSearchRequestTest {
    @Test void normalizedSemanticsAreBoundedAndRedacted() {
        var request = PrivateSearchRequest.decode(Map.of("query", "  CAFÉ   東京  ", "tags", List.of(" Films ", "Work")));
        assertThat(request.query()).isEqualTo("café 東京");
        assertThat(request.tags()).containsExactly("films", "work");
        assertThat(request.lifecycle()).isEqualTo("active");
        assertThat(request.sort()).isEqualTo("relevance");
        assertThat(request.toString()).isEqualTo("PrivateSearchRequest[REDACTED]");
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "a\nb", "a\u0000b"})
    void invalidQueryIsNotReflected(String query) {
        assertThatThrownBy(() -> PrivateSearchRequest.decode(Map.of("query", query)))
                .isInstanceOf(ApiFailureException.class).hasMessage("validation_failed");
    }
    @Test void maximumQueryIsAcceptedButLongerOrMalformedUnicodeIsNot() {
        assertThat(PrivateSearchRequest.decode(Map.of("query", "a".repeat(256))).query()).hasSize(256);
        for (String query : List.of("a".repeat(257), "\ud800")) {
            assertThatThrownBy(() -> PrivateSearchRequest.decode(Map.of("query", query))).isInstanceOf(ApiFailureException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"ownerUserId","userId","accountId","tenantId","provider","semantic","rankWeights","threshold"})
    void rejectsUnapprovedFields(String field) {
        assertThatThrownBy(() -> PrivateSearchRequest.decode(Map.of("query", "safe", field, "no"))).isInstanceOf(ApiFailureException.class);
    }
    @Test void tagsAreExistingNormalizedValuesWithAllTagSemanticsAndBoundedAggregate() {
        for (List<?> tags : List.of(List.of(1), List.of(""), List.of("Work", "work"),
                java.util.Collections.nCopies(11, "a"), List.of("a".repeat(101)),
                List.of("a".repeat(100),"b".repeat(100),"c".repeat(100),"d".repeat(100),"e".repeat(100),"f"))) {
            assertThatThrownBy(() -> PrivateSearchRequest.decode(Map.of("query", "safe", "tags", tags))).isInstanceOf(ApiFailureException.class);
        }
    }
    @Test void noCoercionOrUnknownSortOrLifecycle() {
        for (Map<String,Object> input : List.of(Map.<String,Object>of(), Map.<String,Object>of("query", 1),
                Map.<String,Object>of("query","safe","limit","20"), Map.<String,Object>of("query","safe","cursor",5),
                Map.<String,Object>of("query","safe","sort","updatedAtDesc"), Map.<String,Object>of("query","safe","lifecycle","logically_deleted"))) {
            assertThatThrownBy(() -> PrivateSearchRequest.decode(input)).isInstanceOf(ApiFailureException.class);
        }
    }
    @Test void staticShorthandDoesNotInventSemanticUnderstanding() {
        assertThat(PrivateSearchRequest.decode(Map.of("query","ps")).lexicalVariant()).isEqualTo("playstation");
        assertThat(PrivateSearchRequest.decode(Map.of("query","PlayStation")).lexicalVariant()).isEqualTo("ps");
        assertThat(PrivateSearchRequest.decode(Map.of("query","unknown")).lexicalVariant()).isEqualTo("unknown");
    }
    @Test void everyCandidateBranchHasOwnerAndLifecycleBeforeBudgetOrRanking() {
        String sql = NotesSearchRepository.sql(false);
        for (String branch : List.of("simple_hits", "english_hits", "fuzzy_hits", "tag_hits")) {
            String section = sql.substring(sql.indexOf(branch + " as materialized"));
            section = section.substring(0, section.indexOf("limit :budget"));
            assertThat(section).contains("n.owner_user_id = :owner", "n.lifecycle_state = :lifecycle", "t.owner_user_id = :owner");
            assertThat(section.indexOf("n.owner_user_id = :owner")).isLessThan(section.indexOf("order by"));
        }
        assertThat(sql).doesNotContain("ai_enabled", "note_version", "knowledge.", "for update", "http");
        assertThat(sql).contains("n.revision = f.revision", "limit 100", "60 + row_number()");
        assertThat(sql).doesNotContain("n.markdown", "getString(\"markdown\")");
        assertThat(sql).contains("for 240", "as snippet_source");
        assertThat(sql).doesNotContain("to_tsvector('simple', n.search_body)", "to_tsvector('english', n.search_body)");
        assertThat(sql).contains("ts_filter(n.search_simple, '{A}')", "ts_filter(n.search_simple, '{D}')");
    }
    @Test void searchPolicyCannotSelectUnboundedBudgetsOrDegenerateFuzzyThreshold() {
        assertThatThrownBy(() -> new NotesSearchProperties(51, 0.3, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotesSearchProperties(50, Double.NaN, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotesSearchProperties(50, 0.1, 2000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void presentationReductionPreservesUnicodeUrlAndCodeWithoutHtmlExecution() {
        String raw="## Cafe\u0301\n**Project** `client_id` [guide](https://example.invalid/a-b) <b>東京</b>";
        var projected=SearchTextProjection.of(raw);
        assertThat(projected.text()).isEqualTo("café project client_id [guide](https://example.invalid/a-b) 東京");
        assertThat(projected.toString()).isEqualTo("SearchTextProjection[REDACTED]");
    }
    @Test void snippetClipsOnlyBoundedSqlSourceAndDoesNotSplitAstralCharacters() {
        assertThat(SearchTextProjection.snippet("a".repeat(239)+"🚀"+"suffix"))
                .hasSize(239).doesNotContain("🚀");
        assertThat(SearchTextProjection.snippet("🚀".repeat(240))).hasSize(240);
        assertThatThrownBy(() -> SearchTextProjection.snippet("x".repeat(481))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalizationKeepsComposedHangulAndAstralCharactersWithinPrefixBound() {
        String raw="\u1100\u1161 \ud83d\ude80 Cafe\u0301";
        var projection=SearchTextProjection.of(raw);
        assertThat(projection.text()).isEqualTo("가 🚀 café");
        assertThat(SearchTextProjection.of("🚀".repeat(1024)+"excluded").text()).isEqualTo("🚀".repeat(1024));
    }
    @Test void markdownAutolinksRemainSearchableRatherThanBeingTreatedAsHtmlTags() {
        var text=SearchTextProjection.of("<https://example.test/path> <person@example.test> <b>Text</b>");
        assertThat(text.text()).contains("https://example.test/path", "person@example.test").doesNotContain("<b>");
        assertThat(SearchTextProjection.snippet(text.text()))
                .contains("https://example.test/path").doesNotContain("<",">");
    }
}
