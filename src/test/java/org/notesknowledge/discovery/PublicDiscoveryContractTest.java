package org.notesknowledge.discovery;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class PublicDiscoveryContractTest {
    @ParameterizedTest @ValueSource(strings={"C++","GTA-6","PSN_123","https://example.test/a?q=x+y","goohle"})
    void normalizationPreservesIdentifierAndUrlSymbols(String input){assertThat(PublicDiscoveryService.normalize(" "+input+" ",400)).isEqualTo(input);}
    @Test void normalizationIsIdempotentAndBounded(){String normalized=PublicDiscoveryService.normalize(" Ｆｉｌｍｓ ",64);assertThat(normalized).isEqualTo("Films");assertThat(PublicDiscoveryService.normalize(normalized,64)).isEqualTo(normalized);assertThatThrownBy(()->PublicDiscoveryService.normalize("bad\u0000text",400)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);}
    @Test void deterministicTrendingDecaysAndSaturatesWithoutUniqueViewerClaim(){var now=Instant.parse("2030-01-01T00:00:00Z");double value=PublicDiscoveryService.trending(20,100,now,now);assertThat(value).isGreaterThan(PublicDiscoveryService.trending(20,100,now.minusSeconds(86400),now));assertThat(PublicDiscoveryService.trending(0,0,now,now)).isZero();assertThat(PublicDiscoveryService.trending(Long.MAX_VALUE,Long.MAX_VALUE,now,now)).isFinite();}
    @Test void publicQuerySourcesHaveNoPrivateSqlOrProviderFallback()throws Exception {
        for(String file:new String[]{"discovery/PublicDiscoveryRepository.java","knowledge/PublicKnowledgeRepository.java","knowledge/PublicKnowledgeQueryApi.java","publishing/PublishingPublicSourceAdapter.java"}) {
            String text=Files.readString(Path.of("src/main/java/org/notesknowledge",file));
            assertThat(text).doesNotContain("notes.note","notes.attachment","profile.profile ","knowledge.private_derived", "TextEmbeddingPort", "GoogleDerivationAdapters");
        }
    }
    @Test void telemetryOutageCannotFailPublicReadOrCommittedLike() {
        var registry=org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(registry.counter(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(String[].class)))
            .thenThrow(new IllegalStateException("synthetic metric outage"));
        var service=new PublicDiscoveryService(null,null,null,null,null,registry);
        assertThatCode(()->{service.readMetric(true);service.readMetric(false);service.likeMetric(true);service.likeMetric(false);}).doesNotThrowAnyException();
        org.mockito.Mockito.verify(registry,org.mockito.Mockito.times(4)).counter(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(String[].class));
    }
}
