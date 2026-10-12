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
    @Bean PdfPageRuntime syntheticPdfPageRuntime(){return PdfPageRuntimeFixtures::render;}
    public static PdfChild render(byte[] original,int page) {
        return render(original,page,true);
    }
    static PdfChild renderWithoutLoggingConfiguration(byte[] original,int page) {
        return render(original,page,false);
    }
    private static PdfChild render(byte[] original,int page,boolean loggingConfiguration) {
        try {
            var command=new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
            command.addAll(java.util.List.of("-Xmx192m","-XX:MaxDirectMemorySize=32m","-XX:ActiveProcessorCount=2","-Djava.awt.headless=true"));
            if(loggingConfiguration)command.addAll(java.util.List.of(
                "-Dlogback.configurationFile="+Path.of("src/main/resources/attachments/logback.xml").toAbsolutePath(),
                "-Dlog4j.configurationFile="+Path.of("src/main/resources/attachments/log4j2.xml").toAbsolutePath()));
            command.addAll(java.util.List.of("-cp",System.getProperty("java.class.path"),PdfPageRasterWorker.class.getName()));
            var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            var environment=builder.environment();var bootstrap=new java.util.HashMap<String,String>();
            for(String name:java.util.List.of("SystemRoot","WINDIR","TEMP","TMP"))if(environment.containsKey(name))bootstrap.put(name,environment.get(name));
            environment.clear();environment.putAll(bootstrap);
            return PdfPageWorkerProtocol.exchange(builder.start(),original,page,"controlled-java25-pdfbox-v1",artifact());
        } catch(Exception failure){throw new AssertionError("Synthetic worker failed",failure);}
    }
    public static String transformFingerprint() {
        return PdfChild.transformFingerprint(PdfPageRasterWorker.PROFILE,"controlled-java25-pdfbox-v1",artifact(),
            PdfPageWorkerProtocol.digest(PdfPageRasterWorker.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
