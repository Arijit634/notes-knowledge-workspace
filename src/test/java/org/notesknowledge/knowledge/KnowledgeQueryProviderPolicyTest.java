package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

@Tag("FAST") @Tag("SECURITY")
class KnowledgeQueryProviderPolicyTest {
    private final UUID owner=new UUID(0,1),note=new UUID(0,2);
    private final Expected expected=new Expected(owner,note,null,1,1,null);
    private final String query="Find synthetic Kyoto plans";
    private final AiDerivationProperties config=mock(AiDerivationProperties.class);
    private final ProcessingPolicyService policies=mock(ProcessingPolicyService.class);
    private final PrivateQuerySource sources=mock(PrivateQuerySource.class);
    private final DispatchCoordinator.Handle handle=mock(DispatchCoordinator.Handle.class);
    private final AtomicInteger embeddings=new AtomicInteger(),generations=new AtomicInteger();
    private KnowledgeQueryGate gate(List<String> approvedQueries,List<String> approvedSources) {
        when(config.configured()).thenReturn(true);when(config.provider()).thenReturn("gemini");when(config.tier()).thenReturn("unpaid");when(config.region()).thenReturn("global");
        when(config.approvedPolicyFingerprint()).thenReturn("a".repeat(64));
        when(policies.policyPrerequisite(owner)).thenReturn(Optional.of(new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64))));
        when(sources.matches(any(),eq(true))).thenReturn(true);
        var properties=new ProviderDispatchProperties("unpaid-synthetic-demo",approvedSources,approvedQueries);
        return new KnowledgeQueryGate(policies,config,new ProviderDispatchPolicy(properties),properties,sources);
    }
    private PrivateQuerySource.Source source(){return new PrivateQuerySource.Source(expected,"active",true,"note",Instant.EPOCH,"");}
    private void embed(KnowledgeQueryGate gate,String value){var permit=gate.query(owner,value);permit.requireGoogle(value);embeddings.incrementAndGet();}
    private void generate(KnowledgeQueryGate gate,String value){var permit=gate.evidence(owner,value,List.of(source()),handle);permit.requireGoogle(value);generations.incrementAndGet();}
    private List<String> queries(){return List.of(ProviderDispatchPolicy.queryFingerprint(query));}
    private List<String> sourceApproval(){return List.of(ProviderDispatchPolicy.fingerprint(expected));}
    @Test void defaultDenyPreventsQueryOnlyEmbeddingCapture(){var g=gate(List.of(),List.of());assertThatThrownBy(()->embed(g,query)).isInstanceOf(DerivationFailure.class);assertThat(embeddings).hasValue(0);}
    @Test void approvedSourcesNeverApproveAQuery(){var g=gate(List.of(),sourceApproval());assertThatThrownBy(()->generate(g,query)).isInstanceOf(DerivationFailure.class);assertThat(generations).hasValue(0);}
    @Test void approvedQueryNeverApprovesASource(){var g=gate(queries(),List.of());assertThatThrownBy(()->generate(g,query)).isInstanceOf(DerivationFailure.class);assertThat(generations).hasValue(0);}
    @Test void bothExactApprovalsPermitDispatch(){var g=gate(queries(),sourceApproval());embed(g,query);generate(g,query);assertThat(embeddings).hasValue(1);assertThat(generations).hasValue(1);}
    @Test void oneCharacterDifferenceAndCaseDifferenceFail(){var g=gate(queries(),sourceApproval());assertThatThrownBy(()->embed(g,query+"!")).isInstanceOf(DerivationFailure.class);assertThatThrownBy(()->generate(g,query.toLowerCase(Locale.ROOT))).isInstanceOf(DerivationFailure.class);assertThat(embeddings).hasValue(0);assertThat(generations).hasValue(0);}
    @Test void stripAndNfkcMatchTheExactRequestNormalizationWithoutCaseFolding() throws Exception {
        String raw="  Find synthetic Ｋｙｏｔｏ plans  ";String normalized=new KnowledgeQueryRequest(raw,List.of("active"),true,true).query();
        assertThat(normalized).isEqualTo(query);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("nkw-private-query-v1\n"+query).getBytes(StandardCharsets.UTF_8)));
        assertThat(ProviderDispatchPolicy.queryFingerprint(raw)).isEqualTo(hash);
        var permit=gate(queries(),List.of()).query(owner,raw);permit.requireGoogle(normalized);
        assertThatThrownBy(()->permit.requireGoogle(raw)).isInstanceOf(DerivationFailure.class);
        String compatibilityWhitespace="\u00a0"+raw+"\u00a0";
        assertThat(KnowledgeQueryRequest.normalize(compatibilityWhitespace)).isEqualTo(query);
        assertThat(KnowledgeQueryRequest.normalize(KnowledgeQueryRequest.normalize(compatibilityWhitespace))).isEqualTo(query);
        assertThat(ProviderDispatchPolicy.queryFingerprint(compatibilityWhitespace)).isEqualTo(hash);
    }
    @Test void actualGoogleAdapterRejectsPermitQuerySubstitutionBeforeSdkBoundary() {
        var g=gate(List.of(ProviderDispatchPolicy.queryFingerprint(query),ProviderDispatchPolicy.queryFingerprint(query+"!")),sourceApproval());
        var permit=g.query(owner,query);var evidence=g.evidence(owner,query,List.of(source()),handle);
        @SuppressWarnings("unchecked") ObjectProvider<com.google.genai.Client> clients=mock(ObjectProvider.class);
        var adapter=new GoogleDerivationAdapters().googleStructuredKnowledgePort(config,clients,new ObjectMapper());
        assertThatThrownBy(()->adapter.embedQuery(permit,query+"!")).isInstanceOfSatisfying(DerivationFailure.class,e->assertThat(e.category).isEqualTo(KnowledgeWork.Failure.POLICY_BLOCKED));
        assertThatThrownBy(()->adapter.generate(evidence,query+"!","answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,e->assertThat(e.category).isEqualTo(KnowledgeWork.Failure.POLICY_BLOCKED));
        // No SDK exists in this fixture; the real adapter must fail at approval, not availability.
        verify(clients,times(1)).getIfAvailable();
    }
    @Test void syntheticBypassNeverGrantsGoogleAuthority(){var g=gate(List.of(),List.of());when(config.provider()).thenReturn("synthetic");when(config.tier()).thenReturn("synthetic");
        var permit=g.query(owner,query);permit.requireDispatch();assertThatThrownBy(()->permit.requireGoogle(query)).isInstanceOf(DerivationFailure.class);}
    @Test void boundedMalformedApprovalConfigurationAndRedactedPermit() {
        for(var values:List.of(List.of("bad"),List.of("A".repeat(64)),Collections.nCopies(257,"a".repeat(64))))
            assertThatThrownBy(()->gate(values,List.of())).hasMessage("Invalid provider dispatch policy configuration");
        var g=gate(queries(),sourceApproval());var permit=g.query(owner,query);var evidence=g.evidence(owner,query,List.of(source()),handle);
        assertThat(permit.toString()+evidence.toString()+new ProviderDispatchProperties("unpaid-synthetic-demo",sourceApproval(),queries()))
            .doesNotContain(query,queries().getFirst(),sourceApproval().getFirst());
        assertThatThrownBy(()->permit.requireGoogle(query+"!")).hasMessageNotContaining(query).hasMessageNotContaining(queries().getFirst());
    }
}
