package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY")
class ManagedMediaBoundaryTest {
    private static final String STATUS="NoNewPrivs:\t1\nCapInh:\t0000000000000000\nCapPrm:\t0000000000000000\nCapEff:\t0000000000000000\nCapAmb:\t0000000000000000\n";
    private static final String LIMITS="Limit                     Soft Limit           Hard Limit           Units\nMax processes             300                  300                  processes\n";
    @Test void exactInheritedLimitAndUnprivilegedProcessAreRequired() {
        assertThat(ManagedMediaBoundary.processProtected(STATUS,LIMITS)).isTrue();
        assertThat(ManagedMediaBoundary.processProtected(STATUS.replace("NoNewPrivs:\t1","NoNewPrivs:\t0"),LIMITS)).isFalse();
        for(String capability:java.util.List.of("CapInh","CapPrm","CapEff","CapAmb"))
            assertThat(ManagedMediaBoundary.processProtected(STATUS.replace(capability+":\t0000000000000000",capability+":\t0000000000000001"),LIMITS)).isFalse();
    }
    @Test void missingUnlimitedExcessiveAndNonProcessLimitsAreDenied() {
        for(String limits:java.util.List.of("",LIMITS.replace("300","301"),LIMITS.replace("300","unlimited"),
                LIMITS.replace("300                  processes","unlimited            processes"),LIMITS.replace("processes","files")))
            assertThat(ManagedMediaBoundary.processProtected(STATUS,limits)).isFalse();
        assertThat(ManagedMediaBoundary.processProtected(null,LIMITS)).isFalse();
        assertThat(ManagedMediaBoundary.processProtected(STATUS,null)).isFalse();
        assertThat(ManagedMediaBoundary.processProtected("x".repeat(65537),LIMITS)).isFalse();
        assertThat(ManagedMediaBoundary.processProtected(STATUS,"x".repeat(16385))).isFalse();
    }
}
