package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY")
class MediaContainerBudgetTest {
    @Test void boundedQualificationAndApplicationCeilingsAreAccepted() {
        assertThat(MediaContainerBudget.bounded("1073741824\n","300\n","200000 100000\n")).isTrue();
        assertThat(MediaContainerBudget.bounded("2147483648","512","200000 100000")).isTrue();
        assertThat(MediaContainerBudget.bounded("536870912","256","50000 100000")).isTrue();
    }
    @Test void unboundedExcessiveMissingOrMalformedBudgetsAreDenied() {
        for (String invalid : java.util.List.of("max","","0","-1","2147483649","99999999999999999999"))
            assertThat(MediaContainerBudget.bounded(invalid,"512","200000 100000")).isFalse();
        for (String invalid : java.util.List.of("max","0","513","-1"))
            assertThat(MediaContainerBudget.bounded("2147483648",invalid,"200000 100000")).isFalse();
        for (String invalid : java.util.List.of("max 100000","200001 100000","200000 0","0 100000","200000 100000 extra"))
            assertThat(MediaContainerBudget.bounded("2147483648","512",invalid)).isFalse();
        assertThat(MediaContainerBudget.bounded(null,"512","200000 100000")).isFalse();
    }
}
