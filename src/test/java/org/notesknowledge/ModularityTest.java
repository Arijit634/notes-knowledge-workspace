package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Tag("FAST")
class ModularityTest {

    @Test
    void declaresExactlySevenValidApplicationModules() {
        var modules = ApplicationModules.of(Application.class);

        modules.verify();
        assertThat(modules.stream()).hasSize(7);
    }
}

