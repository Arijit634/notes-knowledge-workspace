package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class WindowsPdfProcessTest {
    private static final boolean WINDOWS=System.getProperty("os.name").startsWith("Windows")&&System.getProperty("os.arch").equals("amd64");
    private static Path owned,runtime,actual,probe;
    private static String actualIdentity,probeIdentity;
    @BeforeAll static void prepare()throws Exception {
        if(!WINDOWS)return;
        owned=AttachmentParserRuntime.privateDirectory();runtime=owned.resolve("runtime");
        var image=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/jlink.exe").toString(),"--add-modules","java.base,java.desktop,java.logging,java.xml","--output",runtime.toString(),"--strip-debug","--no-header-files","--no-man-pages","--compress=zip-6").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertThat(image.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(image.exitValue()).isZero();}finally{image.destroyForcibly();}
        actual=Files.createDirectory(owned.resolve("actual"));probe=Files.createDirectory(owned.resolve("probe"));
        for(Path bundle:java.util.List.of(actual,probe)){Files.createDirectory(bundle.resolve("classes"));Files.createDirectory(bundle.resolve("libraries"));}
        for(String name:java.util.List.of("PdfPageRasterWorker.class","PdfPageRasterWorker$BoundedPng.class")){
            Path destination=actual.resolve("classes/org/notesknowledge/notes/"+name);Files.createDirectories(destination.getParent());
            try(var source=PdfPageRasterWorker.class.getResourceAsStream(name)){Files.copy(source,destination);}
        }
        for(Class<?> dependency:java.util.List.of(org.apache.pdfbox.Loader.class,org.apache.pdfbox.io.IOUtils.class,org.apache.fontbox.FontBoxFont.class,org.apache.commons.logging.LogFactory.class)){
            Path source=Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI());
            Files.copy(source,actual.resolve("libraries").resolve(source.getFileName()));
        }
        Path source=owned.resolve("PdfPageRasterWorker.java");
        try(var bytes=WindowsPdfProcessTest.class.getResourceAsStream("/sandbox/PdfPageRasterWorker.java")){Files.copy(bytes,source);}
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,java.io.OutputStream.nullOutputStream(),java.io.OutputStream.nullOutputStream(),"-d",probe.resolve("classes").toString(),source.toString())).isZero();
        actualIdentity=PdfRuntimeIdentity.fingerprint(runtime,actual,null);
        probeIdentity=PdfRuntimeIdentity.fingerprint(runtime,probe,null);
    }
    private static WindowsPdfProcess start(Path artifacts)throws java.io.IOException {
        return WindowsPdfProcess.start(runtime,artifacts,artifacts.equals(actual)?actualIdentity:probeIdentity);
    }
    @AfterAll static void cleanup(){if(owned!=null)AttachmentParserRuntime.removeDirectory(owned);}
    private void requireUnsupportedDenial() {
        assertThatThrownBy(()->WindowsPdfProcess.start(Path.of("unavailable-runtime"),Path.of("unavailable-artifacts"),null)).isInstanceOf(java.io.IOException.class);
    }
    @Test void realPageWorkerRunsThroughLpacJobAndBoundedBinaryPipes()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(actual);
            var child=PdfPageWorkerProtocol.exchange(process,DerivationMediaFixtures.media("pdf").getBytes(),1,"windows-qualification","0".repeat(64))){
            PdfPageWorkerProtocol.requireRgbPng(child.bytes());assertThat(child.page()).isEqualTo(1);assertThat(process.isAlive()).isFalse();
        }
    }
    @Test void unrelatedPrivateReadAndAllTestedDiskWritesAreDenied()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        Path denied=Files.writeString(owned.resolve("private-synthetic.txt"),"synthetic-not-a-secret");
        try(var process=start(probe)){
            var field=WindowsPdfProcess.class.getDeclaredField("profileDirectory");field.setAccessible(true);
            Path profile=(Path)field.get(process);
            java.util.List<Path> directories;
            try(var tree=Files.walk(profile)){directories=tree.filter(Files::isDirectory).limit(257).toList();}
            assertThat(directories).hasSizeBetween(1,256);
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("files");send.writeUTF(denied.toString());send.writeUTF(probe.resolve("forbidden.bin").toString());send.writeUTF(profile.resolve("forbidden.bin").toString());
            send.writeInt(directories.size());for(Path directory:directories)send.writeUTF(directory.toString());send.close();
            var result=new DataInputStream(process.getInputStream());
            assertThat(result.readBoolean()).as("unrelated private read denied").isTrue();
            assertThat(result.readBoolean()).as("artifact write denied").isTrue();
            assertThat(result.readBoolean()).as("profile-root write denied").isTrue();
            assertThat(result.readBoolean()).as("trusted runtime readable").isTrue();
            for(Path directory:directories)assertThat(result.readBoolean()).as("every existing profile directory rejects writes").isTrue();
            assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }
    }
    @Test void tcpAndUdpLoopbackAreDeniedWithoutNetworkCapabilities()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var tcp=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));var udp=new java.net.DatagramSocket(tcp.getLocalPort(),java.net.InetAddress.getByName("127.0.0.1"));var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("network");send.writeInt(tcp.getLocalPort());send.close();
            var result=new DataInputStream(process.getInputStream());
            for(String transport:java.util.List.of("TCP","UDP")){
                int denied=result.readUnsignedByte();
                if(denied==127)throw new AssertionError("Synthetic probe unavailable: "+result.readUTF()+" / "+result.readUTF()+" / "+result.readUTF());
                int outcome=result.readInt(),reason=result.readInt();
                assertThat(denied).as("%s native result %s, Winsock error %s",transport,outcome,reason).isEqualTo(1);
            }
            assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }
    }
    @Test void childProcessCreationIsRejected()throws Exception {assertProbeBoolean("child");}
    @Test void onlyFixedOsBootstrapEnvironmentIsInherited()throws Exception {assertProbeBoolean("windows-environment");}
    @Test void anUnrelatedInheritableNativeHandleDoesNotReachTheWorker()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var handle=new InheritablePipe();var process=start(probe)){
            var request=new DataOutputStream(process.getOutputStream());request.writeUTF("windows-handle");request.writeLong(handle.read.address());request.close();
            var response=new DataInputStream(process.getInputStream());int first=response.readUnsignedByte();
            if(first=='#'){
                // Windows strict handle checking can raise STATUS_INVALID_HANDLE
                // instead of returning FALSE/ERROR_INVALID_HANDLE. This precise
                // denial kills the JVM; no other fatal exception is accepted.
                String prefix=new String(response.readNBytes(1024),java.nio.charset.StandardCharsets.US_ASCII);
                assertThat(prefix.contains("EXCEPTION_INVALID_HANDLE (0xc0000008)")).as("exact OS invalid-handle denial").isTrue();
                response.transferTo(java.io.OutputStream.nullOutputStream());
                assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isNotZero();
            }else{
                assertThat(first).as("the unrelated handle is absent").isEqualTo(1);
                assertThat(response.read()).isEqualTo(-1);
                assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
            }
        }
    }
    @Test void nativeAllocationAboveJobMemoryLimitIsRejected()throws Exception {assertProbeBoolean("memory");}
    private void assertProbeBoolean(String mode)throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF(mode);send.close();
            assertProbeResult(new DataInputStream(process.getInputStream()));
            assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }
    }
    private void assertProbeResult(DataInputStream result)throws Exception {
        int value=result.readUnsignedByte();
        if(value==127)throw new AssertionError("Synthetic probe unavailable: "+result.readUTF()+" / "+result.readUTF()+" / "+result.readUTF());
        if(value=='#'){
            String prefix=new String(result.readNBytes(1024),java.nio.charset.StandardCharsets.US_ASCII);
            var safe=java.util.regex.Pattern.compile("EXCEPTION_[A-Z_]+|Native memory allocation|insufficient memory|Out of Memory Error").matcher(prefix);
            throw new AssertionError("Synthetic JVM failed: "+(safe.find()?safe.group():"unclassified fatal bootstrap"));
        }
        assertThat(value).isEqualTo(1);
    }
    @Test void cancellationFromAnotherThreadKillsTheJobAndReleasesTheLaunchPermit()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("wait");send.close();
            assertThat(new DataInputStream(process.getInputStream()).readBoolean()).isTrue();
            var handle=process.toHandle();
            try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
                executor.submit(()->{process.destroyForcibly();}).get(10,TimeUnit.SECONDS);
            }
            assertThat(handle.isAlive()).isFalse();assertThat(process.isAlive()).isFalse();
        }
        assertProbeBoolean("child");
    }
    @Test void outputExcessIsRejectedAndTheWriterCanBeTerminated()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("output");send.close();
            assertThatThrownBy(()->process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream())).isInstanceOf(java.io.IOException.class);
            var handle=process.toHandle();process.destroyForcibly();assertThat(handle.isAlive()).isFalse();
        }
    }
    @Test void actualCpuConsumptionIsTerminatedByTheOsJobLimit()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("cpu");send.close();
            assertThat(new DataInputStream(process.getInputStream()).readBoolean()).isTrue();
            // The OS limit is CPU time, not elapsed time. A loaded host can take much
            // longer than ten wall seconds to supply ten seconds of worker CPU.
            assertThat(process.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isNotZero();
            var expired=WindowsPdfProcess.class.getDeclaredField("deadlineExpired");expired.setAccessible(true);
            assertThat(expired.getBoolean(process)).as("CPU ceiling, not the wall watchdog, terminated the spin").isFalse();
        }
    }
    @Test void stalledWorkerIsKilledEvenWithoutAProtocolCaller()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        try(var process=start(probe)){
            var send=new DataOutputStream(process.getOutputStream());send.writeUTF("wait");send.close();
            assertThat(new DataInputStream(process.getInputStream()).readBoolean()).isTrue();
            assertThat(process.waitFor(35,TimeUnit.SECONDS)).isTrue();
            var expired=WindowsPdfProcess.class.getDeclaredField("deadlineExpired");expired.setAccessible(true);
            assertThat(expired.getBoolean(process)).isTrue();
            assertThat(process.toHandle().isAlive()).isFalse();
        }
    }
    @Test void absentRuntimeFailsClosedAndDoesNotPoisonTheLaunchPermit()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        assertThatThrownBy(()->WindowsPdfProcess.start(owned.resolve("absent"),probe,probeIdentity)).isInstanceOf(java.io.IOException.class);
        assertProbeBoolean("child");
    }
    @Test void artifactMismatchDeniesBeforeNativeStartupAndAclsAreRestored()throws Exception {
        if(!WINDOWS){requireUnsupportedDenial();return;}
        var rootAcl=Files.getFileAttributeView(runtime,java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        var artifactAcl=Files.getFileAttributeView(probe,java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        var file=probe.resolve("classes/org/notesknowledge/notes/PdfPageRasterWorker.class");
        var fileAcl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        assertThatThrownBy(()->WindowsPdfProcess.start(runtime,probe,"0".repeat(64))).isInstanceOf(java.io.IOException.class);
        assertProbeBoolean("child");
        assertThat(Files.getFileAttributeView(runtime,java.nio.file.attribute.AclFileAttributeView.class).getAcl()).isEqualTo(rootAcl);
        assertThat(Files.getFileAttributeView(probe,java.nio.file.attribute.AclFileAttributeView.class).getAcl()).isEqualTo(artifactAcl);
        assertThat(Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class).getAcl()).isEqualTo(fileAcl);
    }
    private static final class InheritablePipe implements AutoCloseable {
        private final java.lang.foreign.Arena arena=java.lang.foreign.Arena.ofConfined();
        private final java.lang.invoke.MethodHandle close;
        private java.lang.foreign.MemorySegment read=java.lang.foreign.MemorySegment.NULL,write=java.lang.foreign.MemorySegment.NULL;
        InheritablePipe()throws Exception {
            var p=java.lang.foreign.ValueLayout.ADDRESS;var i=java.lang.foreign.ValueLayout.JAVA_INT;
            var linker=java.lang.foreign.Linker.nativeLinker();
            var library=java.lang.foreign.SymbolLookup.libraryLookup(Path.of(System.getenv("SystemRoot"),"System32/kernel32.dll"),arena);
            close=linker.downcallHandle(library.find("CloseHandle").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,p));
            var create=linker.downcallHandle(library.find("CreatePipe").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,p,p,p,i));
            var send=linker.downcallHandle(library.find("WriteFile").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,p,p,i,p,p));
            var receive=linker.downcallHandle(library.find("ReadFile").orElseThrow(),java.lang.foreign.FunctionDescriptor.of(i,p,p,i,p,p));
            var security=arena.allocate(24,8);security.set(i,0,24);security.set(i,16,1);
            var r=arena.allocate(p);var w=arena.allocate(p);var count=arena.allocate(i);
            try {
                assertThat((int)create.invokeExact(r,w,security,4096)).isEqualTo(1);
                read=r.get(p,0);write=w.get(p,0);
                var bytes=arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE,(byte)1,(byte)2);
                assertThat((int)send.invokeExact(write,bytes,2,count,java.lang.foreign.MemorySegment.NULL)).isEqualTo(1);
                assertThat(count.get(i,0)).isEqualTo(2);
                assertThat((int)receive.invokeExact(read,bytes,1,count,java.lang.foreign.MemorySegment.NULL)).isEqualTo(1);
                assertThat(count.get(i,0)).isEqualTo(1); // Positive control leaves one unread byte.
            }catch(Throwable failure){close();throw new AssertionError("Synthetic native handle setup failed",failure);}
        }
        @Override public void close(){
            try{
                if(read.address()!=0){int result=(int)close.invokeExact(read);assertThat(result).isEqualTo(1);read=java.lang.foreign.MemorySegment.NULL;}
                if(write.address()!=0){int result=(int)close.invokeExact(write);assertThat(result).isEqualTo(1);write=java.lang.foreign.MemorySegment.NULL;}
            }catch(Throwable failure){throw new AssertionError("Synthetic native handle cleanup failed",failure);}
            finally{arena.close();}
        }
    }
}
