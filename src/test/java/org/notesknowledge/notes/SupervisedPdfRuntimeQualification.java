package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit native deployment-custody qualification, absent from the application
 * JAR. Run with administrator-owned immutable runtime/artifacts, an unprivileged
 * dedicated UID, and a private writable recovery directory. Never production
 * acceptance or native ARM64 evidence merely because a controller reports it.
 */
public final class SupervisedPdfRuntimeQualification {
    private SupervisedPdfRuntimeQualification(){ }
    public static void main(String[] arguments)throws Exception {
        if(arguments.length==5&&arguments[3].equals("abandon")){abandon(arguments);return;}
        if(arguments.length!=3||!System.getProperty("os.name").equals("Linux"))throw failed();
        Path helper=Path.of(arguments[0]),runtime=Path.of(arguments[1]),artifacts=Path.of(arguments[2]);
        String identity=PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper);
        Path attempts=Files.createTempDirectory("pdf-runtime-qualification-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        int assertions=0;
        try {
            PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);assertions++;
            PdfLinuxServiceBudget.require();assertions++;
            try(var supervisor=SupervisedPdfPageRuntime.linux(helper,runtime,artifacts,identity,attempts,"native-custody-v1",identity)){
                assertions++; // Actual startup preflight, observed termination and durable cleanup.
                byte[] original=threePages();
                try(var child=supervisor.render(original,2)){
                    var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(child.bytes()));
                    try {
                        if(image==null||image.getWidth()!=128||image.getHeight()!=128||child.page()!=2)throw failed();
                        for(int y=0;y<128;y++)for(int x=0;x<128;x++)if((image.getRGB(x,y)&0xffffff)!=0x0000ff)throw failed();
                    }finally{if(image!=null)image.flush();}
                    assertions+=4;
                }
                assertNoAttempt(attempts);assertions++;
                boolean denied=false;
                try(var ignored=supervisor.render(new byte[]{1,2,3},1)){ }
                catch(org.notesknowledge.websupport.ApiFailureException expected){denied=true;}
                if(!denied)throw failed();assertNoAttempt(attempts);assertions+=2;
                try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                    var first=tasks.submit(()->{try(var child=supervisor.render(original,1)){return child.page();}});
                    var second=tasks.submit(()->{try(var child=supervisor.render(original,3)){return child.page();}});
                    if(first.get(30,TimeUnit.SECONDS)!=1||second.get(30,TimeUnit.SECONDS)!=3)throw failed();
                    assertNoAttempt(attempts);assertions+=3;
                }
            }
            // A mismatched exact artifact identity cannot start a new supervisor.
            boolean denied=false;
            try(var ignored=SupervisedPdfPageRuntime.linux(helper,runtime,artifacts,"0".repeat(64),attempts,"native-custody-v1","0".repeat(64))){ }
            catch(java.io.IOException expected){denied=true;}
            if(!denied)throw failed();assertions++;
            // Actual confined process cancellation while blocked on its input.
            Process child=LinuxPdfProcess.start(helper,runtime,artifacts,identity);
            try(var store=new PdfWorkerAttemptStore(attempts)){
                var attempt=store.record(child);child.destroyForcibly();
                if(!child.waitFor(5,TimeUnit.SECONDS))throw failed();attempt.close();assertNoAttempt(attempts);assertions+=2;
            }finally{child.destroyForcibly();}
            // Kill an actual supervisor JVM, not just a mocked process handle.
            // Its confined blocked worker must die through PDEATHSIG. Recovery
            // observes the recorded creation identity and never signals disk PIDs.
            var command=new java.util.ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                    "-Xmx64m","-cp",System.getProperty("java.class.path"),SupervisedPdfRuntimeQualification.class.getName(),
                    helper.toString(),runtime.toString(),artifacts.toString(),"abandon",attempts.toString()));
            var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);builder.environment().clear();
            Process parent=builder.start();
            try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
              try {
                var ready=tasks.submit(()->new java.io.BufferedReader(new java.io.InputStreamReader(parent.getInputStream(),
                        java.nio.charset.StandardCharsets.US_ASCII)).readLine());
                String line=ready.get(15,TimeUnit.SECONDS);
                if(line==null||!line.matches("[1-9][0-9]{0,18}"))throw failed();
                var worker=ProcessHandle.of(Long.parseLong(line));if(worker.isEmpty()||!worker.get().isAlive())throw failed();
                parent.destroyForcibly();if(!parent.waitFor(5,TimeUnit.SECONDS))throw failed();
                worker.get().onExit().get(10,TimeUnit.SECONDS);
                try(var recovered=new PdfWorkerAttemptStore(attempts)){assertNoAttempt(attempts);assertions+=4;}
              }finally{parent.destroyForcibly();parent.getInputStream().close();}
            }
            // Persistent abandoned record after observed child exit is recovered.
            child=LinuxPdfProcess.start(helper,runtime,artifacts,identity);
            try {
                try(var store=new PdfWorkerAttemptStore(attempts)){store.record(child);}
                child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw failed();
                try(var reopened=new PdfWorkerAttemptStore(attempts)){assertNoAttempt(attempts);assertions++;}
            }finally{child.destroyForcibly();}
            System.out.println("{\"passed\":true,\"assertions\":"+assertions+",\"reportedArchitecture\":\""+
                    System.getProperty("os.arch")+"\",\"productionQualified\":false,\"providerCalls\":0}");
        }finally{
            // Only this generated private directory and its exact lock are removed.
            // Unknown/residual records are deliberately preserved on a failed check.
            try(var entries=Files.list(attempts)){
                if(entries.map(p->p.getFileName().toString()).toList().equals(List.of("worker.lock"))){
                    Files.delete(attempts.resolve("worker.lock"));Files.delete(attempts);
                }
            }
        }
    }
    private static void abandon(String[] arguments)throws Exception {
        Path helper=Path.of(arguments[0]),runtime=Path.of(arguments[1]),artifacts=Path.of(arguments[2]);
        PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);PdfLinuxServiceBudget.require();
        String identity=PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper);
        try(var store=new PdfWorkerAttemptStore(Path.of(arguments[4]))){
            Process child=LinuxPdfProcess.start(helper,runtime,artifacts,identity);store.record(child);
            // Private controller-to-controller pipe only; never a diagnostic/report.
            System.out.println(child.pid());System.out.flush();System.in.read();
            child.destroyForcibly();
        }
    }
    private static void assertNoAttempt(Path directory)throws Exception {
        try(var entries=Files.list(directory)){
            if(!entries.map(p->p.getFileName().toString()).toList().equals(List.of("worker.lock")))throw failed();
        }
    }
    private static byte[] threePages()throws Exception {
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var bytes=new java.io.ByteArrayOutputStream()){
            for(var color:List.of(java.awt.Color.RED,java.awt.Color.BLUE,java.awt.Color.GREEN)){
                var page=new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(64,64));document.addPage(page);
                try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)){
                    stream.setNonStrokingColor(color);stream.addRect(0,0,64,64);stream.fill();
                }
            }
            document.save(bytes);return bytes.toByteArray();
        }
    }
    private static IllegalStateException failed(){return new IllegalStateException("Supervised PDF qualification failed");}
}
