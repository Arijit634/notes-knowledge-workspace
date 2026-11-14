package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY") @EnabledOnOs(OS.LINUX)
class ImmutableAttachmentClasspathTest {
    @TempDir Path directory;
    private Path fixture() throws Exception {
        Path root=Files.createDirectory(directory.resolve("image"));
        for(String name:new String[]{"PdfEvidenceParser.class","PdfAdmissionParser.class","PdfEvidenceParser$1.class",
                "PdfPageRasterWorker.class","PdfPageRasterWorker$BoundedPng.class"}) {
            Path file=root.resolve("classes/org/notesknowledge/notes/"+name);Files.createDirectories(file.getParent());Files.write(file,new byte[]{1});
        }
        Path registry=root.resolve("classes/META-INF/tika/parsers.idx");Files.createDirectories(registry.getParent());Files.writeString(registry,"fixed-fixture-registry");
        Files.createDirectory(root.resolve("libraries"));Files.write(root.resolve("libraries/tika-core-fixture.jar"),new byte[]{1});
        try(var paths=Files.walk(root)) {
            for(Path path:paths.toList()) {
                var permissions=new HashSet<>(Files.getPosixFilePermissions(path));
                permissions.remove(PosixFilePermission.GROUP_WRITE);permissions.remove(PosixFilePermission.OTHERS_WRITE);
                Files.setPosixFilePermissions(path,permissions);
            }
        }
        return root;
    }
    private String validate(Path root) throws Exception {
        // CI's fixture owner differs from the root-owned deployment image.
        // The production entry always supplies ONLY root/0, never this fixture owner.
        return AttachmentParserRuntime.validateImmutableClasspath(root,Set.of(Files.getOwner(root).getName()));
    }
    @Test void fixedLibrariesAndClassRegistryAreUsedWithoutCopyingIntoTemporaryStorage() throws Exception {
        Path root=fixture();long before;try(var paths=Files.walk(root)){before=paths.count();}
        assertThat(validate(root)).isEqualTo(root.resolve("classes")+java.io.File.pathSeparator+root.resolve("libraries")+"/*");
        try(var paths=Files.walk(root)){assertThat(paths.count()).isEqualTo(before);}
    }
    @Test void unknownOperatorProfileCannotSelectAnArbitraryClasspath() {
        assertThatThrownBy(()->AttachmentParserRuntime.childClasspath(directory,"immutable-image-v1:/tmp/untrusted"))
                .isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
    @Test void wrongOwnerCannotSupplyImageCode() throws Exception {
        Path root=fixture();
        assertThatThrownBy(()->AttachmentParserRuntime.validateImmutableClasspath(root,Set.of("never-the-fixture-owner")))
                .isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
    @Test void writableDependencyIsRejected() throws Exception {
        Path root=fixture(),jar=root.resolve("libraries/tika-core-fixture.jar");
        var permissions=new HashSet<>(Files.getPosixFilePermissions(jar));permissions.add(PosixFilePermission.GROUP_WRITE);Files.setPosixFilePermissions(jar,permissions);
        assertThatThrownBy(()->validate(root)).isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
    @Test void symlinkCannotRedirectAClasspathEntry() throws Exception {
        Path root=fixture(),jar=root.resolve("libraries/tika-core-fixture.jar");Files.delete(jar);Files.createSymbolicLink(jar,Files.write(directory.resolve("outside.jar"),new byte[]{1}));
        assertThatThrownBy(()->validate(root)).isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
    @Test void missingRegistryFailsClosed() throws Exception {
        Path root=fixture();Files.delete(root.resolve("classes/META-INF/tika/parsers.idx"));
        assertThatThrownBy(()->validate(root)).isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
    @Test void extraCodeOrUnapprovedLibraryIsRejected() throws Exception {
        Path root=fixture(),extra=root.resolve("classes/org/notesknowledge/notes/Unapproved.class");Files.write(extra,new byte[]{1});
        assertThatThrownBy(()->validate(root)).isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
        Files.delete(extra);Files.write(root.resolve("libraries/unapproved.jar"),new byte[]{1});
        assertThatThrownBy(()->validate(root)).isInstanceOf(IOException.class).hasMessage("Parser classpath unavailable");
    }
}
