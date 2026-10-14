package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** Explicit native-only qualification, never a mandatory provider call or a production bean. */
public final class SupervisedVideoRuntimeQualification {
    public static void main(String[] args)throws Exception {
        if(args.length==4&&args[3].equals("abandon")){abandon(args);return;}
        if(args.length!=6)throw new IllegalArgumentException("Qualification inputs required");
        Path root=Path.of(args[0]),scratch=Files.createTempDirectory("video-qualification-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path records=Files.createTempDirectory("video-records-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path helper=root.resolve("helper"),runtime=root.resolve("runtime"),artifacts=root.resolve("artifacts"),tools=root.resolve("tools");
        String identity=SupervisedVideoClipRuntime.identity(helper,runtime,artifacts,tools);int clips=0,frames=0,checks=0;
        try(var video=SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,identity,"ffmpeg-9.0.2-profile-v1")) {
            for(int fixture=1;fixture<=2;fixture++) {
                byte[] original=Files.readAllBytes(Path.of(args[fixture]));
                var plan=video.plan(original);
                if(plan.durations().size()<500||(double)plan.tick(plan.durations().size())/plan.timescale()<=30)throw new AssertionError("Long fixture required");
                if(fixture==2&&plan.durations().stream().distinct().count()<2)throw new AssertionError("VFR fixture required");
                for(int first=0;first<plan.durations().size();) {
                    int last=plan.next(first);
                    try(var child=video.render(original,plan,first,last)) {
                        if(child.first()!=first||child.last()!=last||!child.plan().equals(plan))throw new AssertionError("Video scope mismatch");
                        Files.write(Path.of(args[3]).resolve("clip-"+fixture+"-"+first+".mp4"),child.bytes());clips++;frames+=last-first;
                    }
                    first=last;
                }
                Arrays.fill(original,(byte)0);
            }
            expectDenial(()->video.plan(new byte[8]));expectDenial(()->video.plan(new byte[20_000_001]));
            // Codec failure and strict rejection are unavailable, never a partial child.
            byte[] broken=Files.readAllBytes(Path.of(args[4]));expectDenial(()->video.plan(broken));
            // Admission is bounded and cancellation remains observable to the caller.
            Thread.currentThread().interrupt();
            expectDenial(()->video.plan(broken));
            if(!Thread.interrupted())throw failed();checks++;
            byte[] original=Files.readAllBytes(Path.of(args[1]));
            try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first=tasks.submit(()->video.plan(original));var second=tasks.submit(()->video.plan(original));
                if(!first.get(35,java.util.concurrent.TimeUnit.SECONDS).equals(second.get(35,java.util.concurrent.TimeUnit.SECONDS)))throw failed();
                checks++;
                var exited=new java.util.concurrent.CountDownLatch(1);
                var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
                var active=tasks.submit(()->{
                    try{video.plan(original);}
                    catch(org.notesknowledge.websupport.ApiFailureException expected){cancelled.set(Thread.currentThread().isInterrupted());}
                    finally{exited.countDown();}
                });
                awaitAttempt(records);active.cancel(true);
                if(!exited.await(10,java.util.concurrent.TimeUnit.SECONDS)||!cancelled.get())throw failed();checks++;
            }finally{Arrays.fill(original,(byte)0);}
            try(var files=Files.list(scratch)){if(files.findAny().isPresent())throw new AssertionError("Scratch leaked");}
            expectStartupDenial(()->SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,identity,"ffmpeg-9.0.2-profile-v1"));checks++;
        }
        expectStartupDenial(()->SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,"0".repeat(64),"ffmpeg-9.0.2-profile-v1"));checks++;
        // Unknown inventory is never opportunistically deleted. All inventory is
        // checked before cleanup, including symlink targets outside this scratch.
        Path unknown=Files.createDirectory(scratch.resolve("video-1"),privateMode(true));
        Path sentinel=Files.createFile(unknown.resolve("keep"),privateMode(false));
        expectStartupDenial(()->SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,identity,"ffmpeg-9.0.2-profile-v1"));
        if(!Files.exists(sentinel))throw failed();Files.delete(sentinel);
        Path link=Files.createSymbolicLink(unknown.resolve("input.mp4"),Path.of(args[1]));
        expectStartupDenial(()->SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,identity,"ffmpeg-9.0.2-profile-v1"));
        Files.delete(link);Files.delete(unknown);checks+=2;
        // Actual parent-JVM death exercises this helper's PDEATHSIG, then the
        // production journal + bounded scratch recovery. No disk PID is signalled.
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx64m","-cp",
            System.getProperty("java.class.path"),SupervisedVideoRuntimeQualification.class.getName(),root.toString(),scratch.toString(),records.toString(),"abandon")
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();Process parent=builder.start();
        try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=tasks.submit(()->new java.io.BufferedReader(new java.io.InputStreamReader(parent.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII)).readLine());
            String line=ready.get(15,java.util.concurrent.TimeUnit.SECONDS);
            if(line==null||!line.matches("[1-9][0-9]{0,18}"))throw failed();
            var worker=ProcessHandle.of(Long.parseLong(line)).orElseThrow();if(!worker.isAlive())throw failed();
            parent.destroyForcibly();if(!parent.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))throw failed();
            worker.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);
            try(var recovered=SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,identity,"ffmpeg-9.0.2-profile-v1")) {
                try(var entries=Files.list(scratch)){if(entries.findAny().isPresent())throw failed();}
            }
            checks+=3;
        }finally{parent.destroyForcibly();parent.getInputStream().close();}
        try(var entries=Files.list(records)){if(entries.count()!=1)throw new AssertionError("Recovery record leaked");}
        Files.delete(records.resolve("worker.lock"));Files.delete(records);Files.delete(scratch);
        System.out.println("{\"passed\":true,\"clips\":"+clips+",\"frames\":"+frames+",\"supervisionChecks\":"+checks+",\"providerCalls\":0,\"productionQualified\":false}");
    }
    private static void abandon(String[] args)throws Exception {
        Path root=Path.of(args[0]),scratch=Path.of(args[1]);
        Path attempt=Files.createDirectory(scratch.resolve("video-2"),privateMode(true));
        Files.createFile(attempt.resolve("input.mp4"),privateMode(false));
        var builder=new ProcessBuilder(root.resolve("helper").toString(),root.resolve("runtime").toString(),root.resolve("artifacts").toString(),
            root.resolve("tools").toString(),attempt.toString(),"plan","0","0","1").directory(attempt.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();Process child=builder.start();
        try(var store=new PdfWorkerAttemptStore(Path.of(args[2]))) {
            store.record(child);System.out.println(child.pid());System.out.flush();System.in.read();
        }finally{child.destroyForcibly();}
    }
    private static void awaitAttempt(Path records)throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until){
            try(var files=Files.list(records)){if(files.anyMatch(p->p.getFileName().toString().endsWith(".state")))return;}
            Thread.yield();
        }
        throw failed();
    }
    @FunctionalInterface private interface Startup {AutoCloseable start()throws Exception;}
    private static void expectStartupDenial(Startup task)throws Exception {
        try(var ignored=task.start()){throw failed();}catch(java.io.IOException expected){ }
    }
    private static java.nio.file.attribute.FileAttribute<?> privateMode(boolean dir){return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));}
    private static IllegalStateException failed(){return new IllegalStateException("Native video supervision qualification failed");}
    private static void expectDenial(Runnable task){try{task.run();throw new AssertionError("Expected safe denial");}catch(org.notesknowledge.websupport.ApiFailureException expected){}}
}
