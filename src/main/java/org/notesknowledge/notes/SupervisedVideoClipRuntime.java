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
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioPlan;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioChild;
import org.notesknowledge.websupport.ApiFailureException;

/** Explicitly selected confined native runtime. Deployment and codec-use
 * qualification remain required; registration never supplies legal clearance. No Docker API,
 * shell, arbitrary executable/options, provider configuration or media logging.
 */
final class SupervisedVideoClipRuntime implements VideoClipRuntime,AutoCloseable {
    static final String PROFILE=VideoAudioChild.PROFILE;
    static final String CONFIGURATION="frame-trim;preserve-vfr-ticks;max1280x720;avc-constrained-baseline;600k;gop25;visual-child-silent;whole-source-aac-to-pcm16k-mono;sample-timeline-zero-fill;no-metadata;unspecified-sar;no-btrt;20s-target;4MiB-visual-child;9600044-audio-child;30s-wall;25s-cpu;x64-as1GiB-arm64-as2GiB;data512MiB;container-ram2GiB-cpu2-pids512;single-child;v2";
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
        return attempt(original,path->readPlan(path,false));
    }
    @Override public VideoAudioPlan audioPlan(byte[] original){return attempt(original,this::readAudioPlan);}
    @Override public VideoAudioChild audio(byte[] original,VideoAudioPlan plan) {
        if(plan==null||!plan.hasAudio())throw failure();
        return attempt(original,path->{
            if(!readAudioPlan(path).equals(plan))throw unavailable();
            Path output=path.resolve("output.wav");Files.createFile(output,permissions(false));
            run(path,"audio",0,Math.toIntExact(plan.samples()),16000,p->{if(p.getInputStream().read()!=-1)throw unavailable();return null;});
            requirePrivate(output,false);long size=Files.size(output);
            if(size!=44+2*plan.samples()||size>9_600_044)throw unavailable();
            byte[] bytes;try(var input=Files.newInputStream(output,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(9_600_045);}
            try {
                if(bytes.length!=size)throw unavailable();
                return new VideoAudioChild(plan,bytes,PROFILE,version,identity,
                    PdfPageWorkerProtocol.digest(CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }finally{java.util.Arrays.fill(bytes,(byte)0);}
        });
    }
    @Override public VideoChild render(byte[] original,VideoPlan plan,int first,int last) {
        if(plan==null||first<0||last<=first||last>plan.durations().size()||plan.tick(last)-plan.tick(first)>30*plan.timescale())throw failure();
        return attempt(original,path->{
            if(!readPlan(path,false).equals(plan))throw unavailable();
            Path output=path.resolve("output.mp4");
            Files.createFile(output,permissions(false));
            run(path,"clip",first,last,plan.timescale(),p->{if(p.getInputStream().read()!=-1)throw unavailable();return null;});
            requirePrivate(output,false);long size=Files.size(output);
            if(size<8||size>4_194_304)throw unavailable();
            Files.delete(path.resolve("input.mp4"));Files.move(output,path.resolve("input.mp4"));
            VideoPlan encoded=readPlan(path,true);
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
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();if(result instanceof VideoChild c)c.close();if(result instanceof VideoAudioChild c)c.close();throw failure();}
        catch(Exception invalid){if(result instanceof VideoChild c)c.close();if(result instanceof VideoAudioChild c)c.close();throw failure();}
        finally {
            try {
                if(path!=null){boolean interrupted=Thread.interrupted();try{clear(path);}catch(IOException uncertain){poisoned=true;throw failure();}
                    finally{if(interrupted)Thread.currentThread().interrupt();}}
            }finally{if(acquired)capacity.release();}
        }
    }
    private VideoPlan readPlan(Path path,boolean canonical)throws Exception {
        return run(path,canonical?"verify":"plan",0,0,1,p->{
            var input=new java.io.DataInputStream(p.getInputStream());
            if(input.readInt()!=0x4e4b5654)throw unavailable();long scale=input.readLong();int count=input.readInt();
            if(count<1||count>18_000)throw unavailable();var deltas=new java.util.ArrayList<Long>(count);
            for(int i=0;i<count;i++)deltas.add(input.readLong());
            if(input.read()!=-1)throw unavailable();return new VideoPlan(scale,deltas);
        });
    }
    private VideoAudioPlan readAudioPlan(Path path)throws Exception {
        return run(path,"inspect",0,0,1,p->{
            var input=new java.io.DataInputStream(p.getInputStream());
            if(input.readInt()!=0x4e4b5641)throw unavailable();
            long scale=input.readLong(),ticks=input.readLong();int hasAudio=input.readUnsignedByte();
            if(hasAudio>1||input.read()!=-1)throw unavailable();return new VideoAudioPlan(scale,ticks,hasAudio==1);
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
            if(!java.util.Set.of("input.mp4","output.mp4","output.wav").contains(name)||Files.size(entry)>(name.equals("input.mp4")?20_000_000:name.equals("output.wav")?9_600_044:4_194_304))throw unavailable();}
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
