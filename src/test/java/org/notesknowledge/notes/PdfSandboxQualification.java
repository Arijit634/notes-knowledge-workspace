package org.notesknowledge.notes;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/** Explicit native qualification entry point; not ordinary CI and not in the
 * production JAR. Run on the named native architecture with the exact reviewed
 * helper build, pinned JDK and test classpath. No provider or real media is used.
 * Successful execution is component evidence, not complete release acceptance.
 */
public final class PdfSandboxQualification {
    private PdfSandboxQualification(){ }

    public static void main(String[] arguments)throws Exception {
        if(arguments.length!=4||!System.getProperty("os.name").equals("Linux"))throw failed();
        String requested=arguments[2],actual=System.getProperty("os.arch");
        if(!List.of("aarch64","amd64").contains(requested)||!requested.equals(actual))throw failed();
        Path helper=Path.of(arguments[0]),runtime=Path.of(arguments[1]);
        if(!arguments[3].matches("[0-9a-f]{64}")||!fileDigest(helper).equals(arguments[3]))throw failed();
        Path owned=AttachmentParserRuntime.privateDirectory();
        try {
            Path real=Files.createDirectory(owned.resolve("actual")),probe=Files.createDirectory(owned.resolve("probe"));
            for(Path bundle:List.of(real,probe)){
                Files.createDirectory(bundle.resolve("classes"));Files.createDirectory(bundle.resolve("libraries"));
            }
            for(String name:List.of("PdfPageRasterWorker.class","PdfPageRasterWorker$BoundedPng.class")){
                Path destination=real.resolve("classes/org/notesknowledge/notes/"+name);Files.createDirectories(destination.getParent());
                try(var source=PdfPageRasterWorker.class.getResourceAsStream(name)){if(source==null)throw failed();Files.copy(source,destination);}
            }
            for(Class<?> dependency:List.of(org.apache.pdfbox.Loader.class,org.apache.pdfbox.io.IOUtils.class,
                    org.apache.fontbox.FontBoxFont.class,org.apache.commons.logging.LogFactory.class)){
                Path source=Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI());
                Files.copy(source,real.resolve("libraries").resolve(source.getFileName()));
            }
            Path source=owned.resolve("PdfPageRasterWorker.java");
            try(var resource=PdfSandboxQualification.class.getResourceAsStream("/sandbox/PdfPageRasterWorker.java")){
                if(resource==null)throw failed();Files.copy(resource,source);
            }
            var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
            if(compiler==null||compiler.run(null,java.io.OutputStream.nullOutputStream(),java.io.OutputStream.nullOutputStream(),
                    "-d",probe.resolve("classes").toString(),source.toString())!=0)throw failed();
            String realIdentity=PdfRuntimeIdentity.fingerprint(runtime,real,helper);
            byte[] pdf=threePages();
            Process process=LinuxPdfProcess.start(helper,runtime,real,realIdentity);
            try(var child=PdfPageWorkerProtocol.exchange(process,pdf,2,"native-component-qualification",realIdentity)){
                var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(child.bytes()));
                if(image==null||image.getWidth()!=128||image.getHeight()!=128||child.page()!=2)throw failed();
                try{for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((image.getRGB(x,y)&0xffffff)!=0x0000ff)throw failed();}
                finally{image.flush();}
            }
            String probeIdentity=PdfRuntimeIdentity.fingerprint(runtime,probe,helper);
            Path privateFixture=Files.writeString(owned.resolve("private-synthetic.txt"),"synthetic-not-a-secret");
            if(!Files.isReadable(privateFixture)||!Files.isWritable(probe))throw failed();
            int assertions=5; // page, dimensions and exact selected-page pixels.
            for(String mode:List.of("linux-network","linux-files","linux-process","linux-prctl","linux-environment","memory")){
                process=LinuxPdfProcess.start(helper,runtime,probe,probeIdentity);
                try {
                    var request=new DataOutputStream(process.getOutputStream());request.writeUTF(mode);
                    if(mode.equals("linux-files")){
                        request.writeUTF(privateFixture.toString());request.writeUTF("/proc/"+ProcessHandle.current().pid()+"/environ");
                        request.writeUTF(probe.resolve("forbidden.bin").toString());request.writeUTF(owned.resolve("forbidden.bin").toString());
                    }
                    request.close();
                    int count=mode.equals("linux-network")?6:mode.equals("linux-files")?5:mode.equals("linux-prctl")?7:1;
                    var response=new DataInputStream(process.getInputStream());
                    for(int n=0;n<count;n++)if(response.readUnsignedByte()!=1)throw failed();
                    if(response.read()!=-1||!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw failed();
                    assertions+=count;
                }finally{process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw failed();}
            }
            for(byte[] invalid:List.of(new byte[]{1,2,3},java.util.Arrays.copyOf(pdf,20))){
                process=LinuxPdfProcess.start(helper,runtime,real,realIdentity);boolean denied=false;
                try(var child=PdfPageWorkerProtocol.exchange(process,invalid,1,"native-component-qualification",realIdentity)){ }
                catch(org.notesknowledge.websupport.ApiFailureException expected){denied=true;}
                if(!denied||process.isAlive())throw failed();assertions++;
            }
            // No claim about ARM64 is emitted by an x64 run. No input/output bytes,
            // private paths, fingerprints or native diagnostics enter the report.
            System.out.println("{\"reportedArchitecture\":\""+actual+"\",\"componentAssertions\":"+assertions+
                    ",\"passed\":true,\"nativeHardwareVerified\":false,\"productionQualified\":false,\"providerCalls\":0}");
        }finally{
            AttachmentParserRuntime.removeDirectory(owned);
            if(Files.exists(owned))throw failed();
        }
    }

    private static byte[] threePages()throws Exception {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            for(var color:List.of(java.awt.Color.RED,java.awt.Color.BLUE,java.awt.Color.GREEN)){
                var page=new PDPage(new PDRectangle(64,64));document.addPage(page);
                try(var content=new PDPageContentStream(document,page)){
                    content.setNonStrokingColor(color);content.addRect(0,0,64,64);content.fill();
                }
            }
            document.save(output);return output.toByteArray();
        }
    }
    private static String fileDigest(Path path)throws Exception {
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)){
            byte[] block=new byte[8192];for(int count;(count=input.read(block))!=-1;)hash.update(block,0,count);
        }
        return java.util.HexFormat.of().formatHex(hash.digest());
    }
    private static IllegalStateException failed(){return new IllegalStateException("Native PDF component qualification failed");}
}
