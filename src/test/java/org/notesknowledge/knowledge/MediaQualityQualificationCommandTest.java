package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class MediaQualityQualificationCommandTest {
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
