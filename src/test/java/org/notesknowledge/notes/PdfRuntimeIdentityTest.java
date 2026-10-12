package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class PdfRuntimeIdentityTest {
    @TempDir Path root;
    @Test void exactArtifactBytesAndNamesBindTheIdentity()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime"));
        Path artifacts=Files.createDirectory(root.resolve("artifacts"));
        Path binary=Files.write(runtime.resolve("trusted-runtime"),new byte[]{1,2,3});
        Files.write(artifacts.resolve("fixed-worker"),new byte[]{4,5,6});
        Path helper=Files.write(root.resolve("helper"),new byte[]{7,8,9});
        String identity=PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper);
        assertThat(identity).matches("[0-9a-f]{64}");
        PdfRuntimeIdentity.require(identity,runtime,artifacts,helper);
        Files.write(binary,new byte[]{3,2,1});
        assertThatThrownBy(()->PdfRuntimeIdentity.require(identity,runtime,artifacts,helper)).isInstanceOf(java.io.IOException.class);
        Files.write(binary,new byte[]{1,2,3});
        assertThat(PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper)).isEqualTo(identity);
        Files.move(binary,runtime.resolve("different-name"));
        assertThatThrownBy(()->PdfRuntimeIdentity.require(identity,runtime,artifacts,helper)).isInstanceOf(java.io.IOException.class);
    }
    @Test void helperChangesAndUnknownApprovalDeny()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        Path helper=Files.write(root.resolve("helper"),new byte[]{1});
        String expected=PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper);
        Files.write(helper,new byte[]{2});
        assertThatThrownBy(()->PdfRuntimeIdentity.require(expected,runtime,artifacts,helper)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->PdfRuntimeIdentity.require(null,runtime,artifacts,helper)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->PdfRuntimeIdentity.require("",runtime,artifacts,helper)).isInstanceOf(java.io.IOException.class);
    }
    @Test void artifactTraversalIsBounded()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        for(int n=0;n<4097;n++)Files.createFile(artifacts.resolve("file-"+n));
        assertThatThrownBy(()->PdfRuntimeIdentity.fingerprint(runtime,artifacts,null)).isInstanceOf(java.io.IOException.class);
    }
    @Test void oversizedRuntimeIsRejectedBeforeReadingItsBytes()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        try(var file=java.nio.channels.FileChannel.open(runtime.resolve("oversized"),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)){
            file.position(512L*1024*1024);file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThatThrownBy(()->PdfRuntimeIdentity.fingerprint(runtime,artifacts,null)).isInstanceOf(java.io.IOException.class);
    }
    @Test void unknownRuntimeCannotBeStarted()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        Path helper=Files.write(root.resolve("unapproved-helper"),new byte[]{1});
        assertThatThrownBy(()->LinuxPdfProcess.start(helper,runtime,artifacts,null)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->LinuxPdfProcess.start(helper,runtime,artifacts,"0".repeat(64))).isInstanceOf(java.io.IOException.class);
    }
    @Test void dotTraversalAndExcessDepthAreRejected()throws Exception {
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        assertThatThrownBy(()->PdfRuntimeIdentity.fingerprint(runtime.resolve("../runtime"),artifacts,null)).isInstanceOf(java.io.IOException.class);
        Path next=artifacts;for(int n=0;n<33;n++)next=Files.createDirectory(next.resolve("d"));
        Files.writeString(next.resolve("unhashed-if-walk-stopped"),"synthetic");
        assertThatThrownBy(()->PdfRuntimeIdentity.fingerprint(runtime,artifacts,null)).isInstanceOf(java.io.IOException.class);
    }
}
