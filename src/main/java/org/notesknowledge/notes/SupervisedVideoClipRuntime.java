package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoChild;
import org.notesknowledge.websupport.ApiFailureException;

/** Implemented native candidate, deliberately NOT registered until deployment,
 * codec-use and supported-platform qualification are complete. No Docker API,
 * shell, arbitrary executable/options, provider configuration or media logging.
 */
final class SupervisedVideoClipRuntime implements VideoClipRuntime,AutoCloseable {
    static final String PROFILE="video-avc-baseline-preserve-ticks-v1";
    static final String CONFIGURATION="frame-trim;preserve-vfr-ticks;max1280x720;avc-constrained-baseline;600k;gop25;no-audio;no-metadata;unspecified-sar;no-btrt;20s-target;4MiB-child;30s-wall;25s-cpu;1GiB-as;single-child;v1";
    private final Path helper,runtime,artifacts,tools,scratch;
    private final String identity,version;
    private final PdfWorkerAttemptStore journal;
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(1);
    private volatile boolean closed,poisoned;
    static String identity(Path helper,Path runtime,Path artifacts,Path tools)throws IOException {
        return PdfPageWorkerProtocol.digest(("nkw-video-runtime-v1\n"+PROFILE+"\n"+CONFIGURATION+"\n"
            +PdfRuntimeIdentity.fingerprint(runtime,artifacts,helper)+"\n"+PdfRuntimeIdentity.fingerprint(runtime,tools,null))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static SupervisedVideoClipRuntime linux(Path helper,Path runtime,Path artifacts,Path tools,Path scratch,Path records,
            String identity,String version)throws IOException {
        if(version==null||!version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")||identity==null||!identity.matches("[0-9a-f]{64}"))throw unavailable();
        PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);PdfLinuxDeploymentCustody.require(helper,runtime,tools);
        PdfLinuxServiceBudget.require();requirePrivate(scratch,true);
        if(!identity.equals(identity(helper,runtime,artifacts,tools)))throw unavailable();
        var journal=new PdfWorkerAttemptStore(records);
        try{return new SupervisedVideoClipRuntime(helper,runtime,artifacts,tools,scratch,identity,version,journal);}
        catch(Exception failed){journal.close();throw unavailable();}
    }
    private SupervisedVideoClipRuntime(Path helper,Path runtime,Path artifacts,Path tools,Path scratch,String identity,
            String version,PdfWorkerAttemptStore journal)throws IOException {
        this.helper=helper;this.runtime=runtime;this.artifacts=artifacts;this.tools=tools;this.scratch=scratch;
        this.identity=identity;this.version=version;this.journal=journal;scavenge();
    }
    @Override public VideoPlan plan(byte[] original) {
        return attempt(original,path->readPlan(path));
    }
    @Override public VideoChild render(byte[] original,VideoPlan plan,int first,int last) {
        if(plan==null||first<0||last<=first||last>plan.durations().size()||plan.tick(last)-plan.tick(first)>30*plan.timescale())throw failure();
        return attempt(original,path->{
            if(!readPlan(path).equals(plan))throw unavailable();
            Path output=path.resolve("output.mp4");
            Files.createFile(output,permissions(false));
            run(path,"clip",first,last,plan.timescale(),p->{if(p.getInputStream().read()!=-1)throw unavailable();return null;});
            requirePrivate(output,false);long size=Files.size(output);
            if(size<8||size>4_194_304)throw unavailable();
            Files.delete(path.resolve("input.mp4"));Files.move(output,path.resolve("input.mp4"));
            VideoPlan encoded=readPlan(path);
            if(encoded.timescale()!=plan.timescale()||!encoded.durations().equals(plan.durations().subList(first,last)))throw unavailable();
            byte[] bytes;
            try(var input=Files.newInputStream(path.resolve("input.mp4"),LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(4_194_305);}
            try {
                if(bytes.length!=size)throw unavailable();
                return new VideoChild(plan,first,last,bytes,PROFILE,version,identity,
                    PdfPageWorkerProtocol.digest(CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }finally{java.util.Arrays.fill(bytes,(byte)0);}
        });
    }
    @FunctionalInterface private interface Effect<T>{T apply(Path path)throws Exception;}
    private <T> T attempt(byte[] original,Effect<T> effect) {
        if(closed||poisoned||original==null||original.length<8||original.length>20_000_000)throw failure();
        Path path=null;T result=null;boolean acquired=false;
        try {
            acquired=capacity.tryAcquire(5,TimeUnit.SECONDS);
            if(!acquired||closed||poisoned)throw unavailable();
            requirePrivate(scratch,true);
            path=Files.createTempDirectory(scratch,"video-",permissions(true));
            // Generated internal name, never a source filename or ID. Recovery accepts
            // Java's decimal random suffix only, and never follows links or recurses.
            Path input=path.resolve("input.mp4");Files.createFile(input,permissions(false));
            try(var out=Files.newOutputStream(input,java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.write(original);}
            result=effect.apply(path);
            clear(path);path=null;return result;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();if(result instanceof VideoChild c)c.close();throw failure();}
        catch(Exception invalid){if(result instanceof VideoChild c)c.close();throw failure();}
        finally {
            try {
                if(path!=null){boolean interrupted=Thread.interrupted();try{clear(path);}catch(IOException uncertain){poisoned=true;throw failure();}
                    finally{if(interrupted)Thread.currentThread().interrupt();}}
            }finally{if(acquired)capacity.release();}
        }
    }
    private VideoPlan readPlan(Path path)throws Exception {
        return run(path,"plan",0,0,1,p->{
            var input=new java.io.DataInputStream(p.getInputStream());
            if(input.readInt()!=0x4e4b5654)throw unavailable();long scale=input.readLong();int count=input.readInt();
            if(count<1||count>18_000)throw unavailable();var deltas=new java.util.ArrayList<Long>(count);
            for(int i=0;i<count;i++)deltas.add(input.readLong());
            if(input.read()!=-1)throw unavailable();return new VideoPlan(scale,deltas);
        });
    }
    @FunctionalInterface private interface Read<T>{T apply(Process process)throws Exception;}
    private <T> T run(Path path,String mode,int first,int last,long scale,Read<T> read)throws Exception {
        PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);PdfLinuxDeploymentCustody.require(helper,runtime,tools);
        PdfLinuxServiceBudget.require();if(!identity.equals(identity(helper,runtime,artifacts,tools)))throw unavailable();
        var builder=new ProcessBuilder(helper.toString(),runtime.toString(),artifacts.toString(),tools.toString(),path.toString(),
            mode,Integer.toString(first),Integer.toString(last),Long.toString(scale));
        builder.environment().clear();builder.directory(path.toFile());builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child=builder.start();PdfWorkerAttemptStore.Attempt record=null;
        try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                try{record=journal.record(child);}catch(IOException uncertain){poisoned=true;throw uncertain;}
                child.getOutputStream().write(0xa5);child.getOutputStream().close();
                var future=tasks.submit(()->read.apply(child));
                try {T result=future.get(30,TimeUnit.SECONDS);
                    if(!child.waitFor(1,TimeUnit.SECONDS)||child.exitValue()!=0)throw unavailable();return result;
                }finally{terminate(child);future.cancel(true);}
            }finally {
                boolean interrupted=Thread.interrupted();
                try{terminate(child);if(record!=null)record.close();}
                catch(Exception uncertain){poisoned=true;throw unavailable();}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            }
        }
    }
    private static void terminate(Process child)throws Exception {
        boolean interrupted=Thread.interrupted();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {child.destroyForcibly();while(child.isAlive()){
            long left=end-System.nanoTime();if(left<=0)throw unavailable();
            try{child.waitFor(left,TimeUnit.NANOSECONDS);}catch(InterruptedException cancelled){interrupted=true;}
        }
        child.getInputStream().close();child.getOutputStream().close();child.getErrorStream().close();
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private void scavenge()throws IOException {
        List<Path> paths;try(var list=Files.list(scratch)){paths=list.limit(5).toList();}
        if(paths.size()>4)throw unavailable();
        for(Path path:paths)inventory(path); // validate entire inventory before deleting ANYTHING
        for(Path path:paths)clear(path);
    }
    private static List<Path> inventory(Path path)throws IOException {
        requirePrivate(path,true);if(!path.getFileName().toString().matches("video-[0-9]{1,24}"))throw unavailable();
        List<Path> entries;try(var list=Files.list(path)){entries=list.limit(3).toList();}if(entries.size()>2)throw unavailable();
        for(Path entry:entries){requirePrivate(entry,false);String name=entry.getFileName().toString();
            if(!java.util.Set.of("input.mp4","output.mp4").contains(name)||Files.size(entry)>(name.equals("input.mp4")?20_000_000:4_194_304))throw unavailable();}
        return entries;
    }
    private static void clear(Path path)throws IOException {for(Path entry:inventory(path))Files.delete(entry);Files.delete(path);}
    private static void requirePrivate(Path path,boolean directory)throws IOException {
        PdfRuntimeIdentity.noLinks(path);
        if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"))
                ||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(Path.of("/proc/self")))
                ||directory&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                ||!directory&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1))throw unavailable();
    }
    private static java.nio.file.attribute.FileAttribute<?> permissions(boolean directory){return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}
    @Override public void close()throws IOException {
        closed=true;boolean acquired=false;
        try {
            // A render has at most three individually bounded child processes.
            acquired=capacity.tryAcquire(110,TimeUnit.SECONDS);
            if(!acquired){poisoned=true;throw unavailable();}
            journal.close();
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw unavailable();}
        finally{if(acquired)capacity.release();}
    }
    private static IOException unavailable(){return new IOException("Isolated video processing unavailable");}
    private static ApiFailureException failure(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
