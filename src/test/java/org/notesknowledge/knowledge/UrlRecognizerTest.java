package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("RETRIEVAL")
class UrlRecognizerTest {
    @Test void markdownAutolinksAndPlainUrlsRetainOffsetsAndOriginalDisplay() {
        String text="[saved](HTTPS://Example.test/A?x=1#part) <https://example.test/other> https://example.test/two.";
        var matches=new ArrayList<UrlRecognizer.Match>();
        assertThat(new UrlRecognizer().scan(text,matches::add)).isFalse();
        assertThat(matches).hasSize(3);
        assertThat(matches.getFirst().comparisonKey()).isEqualTo("https://example.test/A?x=1#part");
        matches.forEach(m->assertThat(text.substring(m.offset(),m.offset()+m.length())).isEqualTo(m.display()));
    }
    @Test void normalizationDoesNotCollapseMeaningfulPathQueryFragmentPortOrEscapes() {
        var matches=new ArrayList<UrlRecognizer.Match>();
        new UrlRecognizer().scan("https://example.test/A https://example.test/a https://example.test/A?x=1 "
                +"https://example.test/A#x https://example.test:443/A https://example.test/%41",matches::add);
        assertThat(matches.stream().map(UrlRecognizer.Match::comparisonKey).distinct()).hasSize(6);
    }
    @Test void inertUnsupportedTextIsNotFetchedAndNestedBalancedParenthesesArePreserved() {
        var matches=new ArrayList<UrlRecognizer.Match>();
        new UrlRecognizer().scan("javascript:alert(1) file:///tmp/path ftp://example.test/x https://bad_host/x "
                +"https://user:synthetic@example.test/x https://example.test/wiki/(one(two))",matches::add);
        assertThat(matches).hasSize(2);
        assertThat(matches.getLast().display()).isEqualTo("https://example.test/wiki/(one(two))");
    }
    @Test void resourceLimitIsTruthfulRatherThanSilentlyComplete() {
        var matches=new ArrayList<UrlRecognizer.Match>();
        assertThat(new UrlRecognizer().scan("https://example.test/"+"a".repeat(2049)+" https://example.test/end",matches::add)).isTrue();
        assertThat(matches).hasSize(1);
    }
    @Test void caseSensitiveUserInfoAndIpv6ZoneAndQueryPunctuationRemainDistinct() {
        var matches=new ArrayList<UrlRecognizer.Match>();
        new UrlRecognizer().scan("https://Viewer@example.test/a https://viewer@EXAMPLE.test/a "
                +"https://[fe80::1%ETH0]/a https://[fe80::1%eth0]/a https://example.test/?value=wow!",matches::add);
        assertThat(matches.stream().map(UrlRecognizer.Match::comparisonKey).distinct()).hasSize(5);
        assertThat(matches.getLast().comparisonKey()).isEqualTo("https://example.test/?value=wow!");
    }
    @Test void typesAndBoundsDoNotExposePrivateMaterialThroughToString() {
        assertThat(new UrlRecognizer.Match("synthetic private value","synthetic private value",0,1).toString()).doesNotContain("synthetic");
        assertThatThrownBy(()->new DeterministicExtractionProperties(0,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DeterministicExtractionProperties(1,5001)).isInstanceOf(IllegalArgumentException.class);
    }
}
