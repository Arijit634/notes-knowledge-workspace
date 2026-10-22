package org.notesknowledge.notes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Trusted SYNTHETIC input launcher only. Real worker/process/protocol, NOT OS isolation evidence.
 * Test classes/this bean are absent from the production JAR. No environment key is inherited.
 */
@TestConfiguration(proxyBeanMethods=false)
public class PdfPageRuntimeFixtures {
    private static volatile SupervisedPdfPageRuntime qualifiedRuntime;
    private static volatile Path qualifiedAttempts;
    @Bean PdfPageRuntime syntheticPdfPageRuntime(){return PdfPageRuntimeFixtures::render;}
    public static PdfChild render(byte[] original,int page) {
        return render(original,page,true);
    }
    static PdfChild renderWithoutLoggingConfiguration(byte[] original,int page) {
        return render(original,page,false);
    }
    private static PdfChild render(byte[] original,int page,boolean loggingConfiguration) {
        try {
            if(nativeQualificationRequested())return NativeQualification.RUNTIME.render(original,page);
            return PdfPageWorkerProtocol.exchange(start(loggingConfiguration),original,page,"controlled-java25-pdfbox-v1",artifact());
        } catch(Exception failure){throw new AssertionError("Synthetic worker failed",failure);}
    }
    static Process start(boolean loggingConfiguration)throws java.io.IOException {
        Path launchDirectory=AttachmentParserRuntime.privateDirectory();
        try {
            var command=new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
            command.addAll(java.util.List.of("-Xmx192m","-XX:MaxDirectMemorySize=32m","-XX:ActiveProcessorCount=2","-Djava.awt.headless=true"));
            if(loggingConfiguration)command.addAll(java.util.List.of(
                "-Dlogback.configurationFile="+Path.of("src/main/resources/attachments/logback.xml").toAbsolutePath(),
                "-Dlog4j.configurationFile="+Path.of("src/main/resources/attachments/log4j2.xml").toAbsolutePath()));
            command.addAll(java.util.List.of("-cp",AttachmentParserRuntime.compactClasspath(launchDirectory,
                System.getProperty("java.class.path")),PdfPageRasterWorker.class.getName()));
            var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            var environment=builder.environment();var bootstrap=new java.util.HashMap<String,String>();
            for(String name:java.util.List.of("SystemRoot","WINDIR","TEMP","TMP"))if(environment.containsKey(name))bootstrap.put(name,environment.get(name));
            environment.clear();environment.putAll(bootstrap);
            Process process=builder.start();
            process.onExit().thenRun(()->AttachmentParserRuntime.removeDirectory(launchDirectory));
            return process;
        } catch(java.io.IOException|RuntimeException failure) {
            AttachmentParserRuntime.removeDirectory(launchDirectory);throw failure;
        }
    }
    public static String transformFingerprint() {
        if(nativeQualificationRequested())return PdfChild.transformFingerprint(PdfPageRasterWorker.PROFILE,"controlled-linux-custody-v1",
                NativeQualification.IDENTITY,PdfPageWorkerProtocol.digest(PdfPageRasterWorker.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return PdfChild.transformFingerprint(PdfPageRasterWorker.PROFILE,"controlled-java25-pdfbox-v1",artifact(),
            PdfPageWorkerProtocol.digest(PdfPageRasterWorker.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    // Explicit local qualification only: this entire class is test-only. Mandatory
    // CI has no such property and never assumes OS containment from a test double.
    private static boolean nativeQualificationRequested(){return System.getProperty("nkw.test.pdf.nativeRoot")!=null;}
    public static void closeNativeQualification() {
        var runtime=qualifiedRuntime;if(runtime==null)return;
        try {
            runtime.close();
            try(var entries=Files.list(qualifiedAttempts)){
                if(!entries.map(p->p.getFileName().toString()).toList().equals(java.util.List.of("worker.lock")))
                    throw new AssertionError("Native PDF attempt cleanup incomplete");
            }
            Files.delete(qualifiedAttempts.resolve("worker.lock"));Files.delete(qualifiedAttempts);
        }catch(Exception failure){throw new AssertionError("Native PDF shutdown qualification failed");}
    }
    private static final class NativeQualification {
        static final Path ROOT=Path.of(System.getProperty("nkw.test.pdf.nativeRoot"));
        static final String IDENTITY=identity();
        static final SupervisedPdfPageRuntime RUNTIME=runtime();
        private static String identity(){
            try{return PdfRuntimeIdentity.fingerprint(ROOT.resolve("runtime"),ROOT.resolve("artifacts"),ROOT.resolve("helper"));}
            catch(Exception failure){throw new AssertionError("Native PDF identity qualification failed");}
        }
        private static SupervisedPdfPageRuntime runtime(){
            try {
                Path attempts=Files.createTempDirectory("pdf-native-pipeline-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                var runtime=SupervisedPdfPageRuntime.linux(ROOT.resolve("helper"),ROOT.resolve("runtime"),ROOT.resolve("artifacts"),
                        IDENTITY,attempts,"controlled-linux-custody-v1",IDENTITY);
                qualifiedAttempts=attempts;qualifiedRuntime=runtime;
                return runtime;
            }catch(Exception failure){throw new AssertionError("Native PDF deployment qualification failed");}
        }
    }
    private static final class Artifact {static final String HASH=artifactHash();}
    private static String artifact(){return Artifact.HASH;}
    private static String artifactHash() {
        try(var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes)) {
            // Exact worker class bytes and every JAR on THIS synthetic launcher's classpath.
            // This is not a claim that a Windows artifact is an ARM64 runtime qualification.
            for(String name:java.util.List.of("/org/notesknowledge/notes/PdfPageRasterWorker.class",
                    "/org/notesknowledge/notes/PdfPageRasterWorker$BoundedPng.class"))
                try(var input=PdfPageRasterWorker.class.getResourceAsStream(name)) {out.writeUTF(name);out.writeUTF(PdfPageWorkerProtocol.digest(input.readAllBytes()));}
            for(String entry:System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path path=Path.of(entry);
                if(Files.isRegularFile(path)&&entry.endsWith(".jar")) {
                    out.writeUTF(path.getFileName().toString());
                    try(var input=Files.newInputStream(path)) {
                        var hash=java.security.MessageDigest.getInstance("SHA-256");byte[] block=new byte[8192];int read;
                        while((read=input.read(block))!=-1)hash.update(block,0,read);
                        out.writeUTF(java.util.HexFormat.of().formatHex(hash.digest()));
                    }
                }
            }
            return PdfPageWorkerProtocol.digest(bytes.toByteArray());
        } catch(Exception failure){throw new AssertionError("Synthetic artifact identity failed",failure);}
    }
}
