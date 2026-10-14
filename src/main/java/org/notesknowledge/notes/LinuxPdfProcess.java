package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Unregistered Linux launch primitive. The fixed native helper installs the
 * boundary before exec; it is never a privileged helper or a container client.
 * The operator artifact fingerprint is necessary but is NOT OS qualification.
 * Native ARM64 qualification, resource/custody tests and deployment preflight
 * must pass before a PdfPageRuntime may use this primitive in production.
 */
final class LinuxPdfProcess {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("nkw-pdf-linux-deadline").factory());
    private LinuxPdfProcess(){ }

    static Process start(Path helper,Path runtime,Path artifacts,String expectedIdentity)throws IOException {
        String architecture=System.getProperty("os.arch");
        if(!System.getProperty("os.name").equals("Linux")
                ||!java.util.Set.of("amd64","x86_64","aarch64").contains(architecture))throw unavailable();
        PdfRuntimeIdentity.require(expectedIdentity,runtime,artifacts,helper);
        if(!Files.isRegularFile(helper,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(helper))throw unavailable();
        requireElf(helper,architecture);requireElf(runtime.resolve("bin/java"),architecture);
        // No shell, inherited provider configuration, inherited stderr, arbitrary
        // argument list, selected class or input-media path is available here.
        var builder=new ProcessBuilder(helper.toRealPath().toString(),runtime.toRealPath().toString(),artifacts.toRealPath().toString());
        builder.environment().clear();builder.directory(artifacts.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        var managed=new Managed(process);
        var deadline=DEADLINES.schedule(managed::destroyForcibly,30,TimeUnit.SECONDS);
        process.onExit().thenRun(()->deadline.cancel(false));
        return managed;
    }
    private static void requireElf(Path path,String architecture)throws IOException {
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] header=input.readNBytes(64);
            if(header.length!=64||header[0]!=0x7f||header[1]!='E'||header[2]!='L'||header[3]!='F'
                    ||header[4]!=2||header[5]!=1||header[6]!=1)throw unavailable();
            var elf=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int type=Short.toUnsignedInt(elf.getShort(16)),machine=Short.toUnsignedInt(elf.getShort(18));
            if(type!=2&&type!=3||machine!=(architecture.equals("aarch64")?183:62))throw unavailable();
        }
    }
    private static IOException unavailable(){return new IOException("Isolated PDF worker unavailable");}

    private static final class Managed extends Process {
        private final Process child;
        Managed(Process child){this.child=child;}
        @Override public java.io.OutputStream getOutputStream(){return child.getOutputStream();}
        @Override public java.io.InputStream getInputStream(){return child.getInputStream();}
        @Override public java.io.InputStream getErrorStream(){return child.getErrorStream();}
        @Override public long pid(){return child.pid();}
        @Override public ProcessHandle toHandle(){return child.toHandle();}
        @Override public boolean isAlive(){return child.isAlive();}
        @Override public int waitFor()throws InterruptedException{return child.waitFor();}
        @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return child.waitFor(timeout,unit);}
        @Override public int exitValue(){return child.exitValue();}
        @Override public void destroy(){destroyForcibly();}
        @Override public Process destroyForcibly(){
            // seccomp permits pthreads only, never another process. Wait for the
            // whole process to die; Process.destroyForcibly() alone is asynchronous.
            boolean interrupted=Thread.interrupted();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try {
                child.destroyForcibly();
                while(child.isAlive()){
                    long remaining=deadline-System.nanoTime();
                    if(remaining<=0)throw new IllegalStateException("PDF worker termination unavailable");
                    try{child.waitFor(remaining,TimeUnit.NANOSECONDS);}
                    catch(InterruptedException cancellation){interrupted=true;}
                }
                return this;
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
}
