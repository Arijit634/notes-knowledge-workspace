package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Tag("FAST")
class ModularityTest {

    @Test
    void httpControllersDoNotOwnPersistenceOrTransactionBoundaries() {
        var production = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.notesknowledge");
        var shortcuts = production.stream()
                .filter(type -> type.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> {
                    String target = dependency.getTargetClass().getSimpleName();
                    return target.endsWith("Repository") || target.endsWith("Persistence")
                            || target.equals("JdbcTemplate") || target.equals("JdbcOperations")
                            || target.equals("TransactionTemplate") || target.equals("TransactionOperations")
                            || target.equals("PlatformTransactionManager") || target.equals("Transactional");
                })
                .map(Object::toString).sorted().toList();
        assertThat(shortcuts).as("HTTP controllers delegate persistence and transactions to application services")
                .isEmpty();
    }

    @Test
    void declaresExactlySevenValidApplicationModules() {
        var modules = ApplicationModules.of(Application.class);

        modules.verify();
        assertThat(modules.stream()).hasSize(7);
    }
}

