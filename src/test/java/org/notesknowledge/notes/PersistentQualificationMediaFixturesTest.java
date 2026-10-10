package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class PersistentQualificationMediaFixturesTest {
    @TempDir Path directory;
    @Test void syntheticCustodySurvivesStoreReconstructionAndBoundsRange()throws Exception {
        var environment=new MockEnvironment().withProperty("synthetic.qualification.object-directory",directory.toString());
        var first=new PersistentQualificationMediaFixtures().persistentSyntheticStore(environment);
        first.write("synthetic-reference",new ByteArrayInputStream(new byte[]{1,2,3,4,5}),5);
        var restarted=new PersistentQualificationMediaFixtures().persistentSyntheticStore(environment);
        try(var input=restarted.openRange("synthetic-reference",1,2)){assertThat(input.readAllBytes()).containsExactly(2,3);}
        assertThatThrownBy(()->restarted.write("synthetic-reference",new ByteArrayInputStream(new byte[]{9}),1)).isInstanceOf(IllegalStateException.class);
        restarted.delete("synthetic-reference");restarted.delete("synthetic-reference");
        assertThatThrownBy(()->restarted.openRange("synthetic-reference",0,1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void absentExplicitDirectoryCannotCreateFallbackStorage() {
        assertThatThrownBy(()->new PersistentQualificationMediaFixtures().persistentSyntheticStore(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }
}
