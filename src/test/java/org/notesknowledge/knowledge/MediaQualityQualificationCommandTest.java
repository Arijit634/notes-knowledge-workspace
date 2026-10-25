package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class MediaQualityQualificationCommandTest {
    @Test void claimSelectionRequiresTheExactSourceKindAndApprovedLineage() {
        var source=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(new UUID(0,1),new UUID(0,2),new UUID(0,3),1,1,1L);
        var other=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(new UUID(0,4),new UUID(0,2),new UUID(0,3),1,1,1L);
        var stale=claim(source,KnowledgeWork.Kind.ATTACHMENT,"old-lineage");
        var current=claim(source,KnowledgeWork.Kind.ATTACHMENT,"current-lineage");
        assertThat(MediaQualityQualificationCommand.matchingClaim(List.of(stale,claim(other,KnowledgeWork.Kind.ATTACHMENT,"current-lineage"),claim(source,KnowledgeWork.Kind.NOTE,"current-lineage"),current),source,"current-lineage")).contains(current);
        assertThat(MediaQualityQualificationCommand.matchingClaim(List.of(stale),source,"current-lineage")).isEmpty();
    }
    private static KnowledgeWork.Claim claim(org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected source,KnowledgeWork.Kind kind,String lineage){
        var claim=org.mockito.Mockito.mock(KnowledgeWork.Claim.class);
        org.mockito.Mockito.when(claim.intent()).thenReturn(new KnowledgeWork.Intent(new UUID(0,5),kind,source,"claimed",1,5,"text_surrogate",lineage));return claim;
    }
    @Test void liveExecutionRequiresSeparateConsentFreeTierAndExactPlanApproval(){
        byte[] plan="fictional immutable plan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var approved=new HashMap<String,String>();approved.put("NKW_GEMINI_QUALIFICATION_APPROVED","true");approved.put("NKW_GEMINI_FREE_TIER_CONFIRMED","true");
        approved.put("NKW_GEMINI_APPROVED_PLAN_SHA256",QualificationJournal.hash(plan));
        MediaQualityQualificationCommand.requireExecutionConsent(approved,plan);
        for(String name:List.copyOf(approved.keySet())){var missing=new HashMap<>(approved);missing.remove(name);
            assertThatThrownBy(()->MediaQualityQualificationCommand.requireExecutionConsent(missing,plan)).isInstanceOf(IllegalStateException.class);}
        assertThatThrownBy(()->MediaQualityQualificationCommand.requireExecutionConsent(approved,new byte[]{1,2})).isInstanceOf(IllegalStateException.class);
    }
    @Test void arbitraryFilesAndUnknownOrDuplicateFixturesAreNotAccepted()throws Exception {
        assertThat(MediaQualityQualificationCommand.fixtureSelection("image,text-pdf,scan-pdf,long-video")).hasSize(4);
        for(String value:List.of("../user-media","image,image","unknown","image,"))
            assertThatThrownBy(()->MediaQualityQualificationCommand.fixtureSelection(value)).isInstanceOf(IllegalStateException.class);
    }
}
