package org.notesknowledge.knowledge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY")
class KnowledgeBoundaryTest {
    @Test void knowledgeCannotReachNotesInternalsOrNetworkClients() {
        var classes=new ClassFileImporter().importPaths(Path.of("target/classes"));
        noClasses().that().resideInAPackage("..knowledge..").should().dependOnClassesThat().resideInAPackage("..notes..").check(classes);
        for(String denied:java.util.List.of("java.net.URL","java.net.URLConnection","java.net.Socket","java.net.http.HttpClient"))
            noClasses().that().resideInAPackage("..knowledge..").should().dependOnClassesThat().haveFullyQualifiedName(denied).check(classes);
        noClasses().that().resideInAPackage("..notes..").should().dependOnClassesThat().haveSimpleName("KnowledgeWorkRepository").check(classes);
    }
    @Test void durableOperationsAreNotHttpOrScheduledAndOnlyMetadataPrerequisitesExist() {
        assertThat(Arrays.stream(KnowledgeWorkService.class.getDeclaredMethods()).flatMap(m->Arrays.stream(m.getAnnotations()))
                .map(a->a.annotationType().getName())).noneMatch(n->n.startsWith("org.springframework.web.bind.annotation")||n.equals("org.springframework.scheduling.annotation.Scheduled"));
        assertThat(KnowledgeWork.Failure.values()).hasSize(9);
        assertThat(KnowledgeWork.Kind.values()).hasSize(2);
        assertThat(KnowledgeWorkService.POLICY.maximumBatchSize()).isEqualTo(10);
        assertThat(KnowledgeWorkService.POLICY.leaseDuration()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(KnowledgeWorkRepository.MAX_ATTEMPTS).isEqualTo(5);
    }
}
