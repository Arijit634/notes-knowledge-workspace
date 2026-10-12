package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;

/** Real filesystem/transport lifecycle; trusted uncontained test children are
 * NOT OS-boundary evidence. Native custody/isolation is qualified separately. */
@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class SupervisedPdfPageRuntimeTest {
    @TempDir Path root;
    private boolean unsupported(){
        if(System.getProperty("os.name").equals("Linux"))return false;
        assertThatThrownBy(()->new PdfWorkerAttemptStore(root)).isInstanceOf(java.io.IOException.class);return true;
    }
    private PdfWorkerAttemptStore store()throws Exception {
        return new PdfWorkerAttemptStore(Files.createDirectory(root.resolve("attempts"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))));
    }
    private void noAttemptRemains()throws Exception {
        try(var paths=Files.list(root.resolve("attempts"))){assertThat(paths.map(p->p.getFileName().toString()).toList()).containsExactly("worker.lock");}
    }
    @Test void actualWorkerReturnsOnlyAfterTerminationAndPersistentCleanup()throws Exception {
        if(unsupported())return;
        var process=new java.util.concurrent.atomic.AtomicReference<Process>();
        try(var runtime=new SupervisedPdfPageRuntime(()->{var child=PdfPageRuntimeFixtures.start(false);process.set(child);return child;},store(),"synthetic-supervisor-v1","a".repeat(64));
                var child=runtime.render(DerivationMediaFixtures.media("pdf").getBytes(),1)){
            assertThat(child.page()).isEqualTo(1);PdfPageWorkerProtocol.requireRgbPng(child.bytes());
            assertThat(process.get().isAlive()).isFalse();noAttemptRemains();
        }
    }
    @Test void malformedPdfCannotReturnEvidenceOrLeaveAttemptState()throws Exception {
        if(unsupported())return;
        var process=new java.util.concurrent.atomic.AtomicReference<Process>();
        try(var runtime=new SupervisedPdfPageRuntime(()->{var child=PdfPageRuntimeFixtures.start(false);process.set(child);return child;},store(),"synthetic-supervisor-v1","a".repeat(64))){
            assertThatThrownBy(()->runtime.render(new byte[]{1,2,3},1)).isInstanceOfSatisfying(ApiFailureException.class,
                    e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
            assertThat(process.get().isAlive()).isFalse();noAttemptRemains();
        }
    }
    @Test void invalidBoundsAndClosedRuntimeNeverLaunch()throws Exception {
        if(unsupported())return;
        var starts=new java.util.concurrent.atomic.AtomicInteger();
        var runtime=new SupervisedPdfPageRuntime(()->{starts.incrementAndGet();throw new java.io.IOException();},store(),"synthetic-v1","a".repeat(64));
        assertThatThrownBy(()->runtime.render(new byte[]{1},101)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(()->runtime.render(null,1)).isInstanceOf(ApiFailureException.class);
        runtime.close();assertThatThrownBy(()->runtime.render(new byte[]{1},1)).isInstanceOf(ApiFailureException.class);
        assertThat(starts).hasValue(0);
    }
    @Test void cancellationTerminatesBlockedWorkerBeforeClearingItsRecord()throws Exception {
        if(unsupported())return;
        var started=new java.util.concurrent.CountDownLatch(1);var process=new java.util.concurrent.atomic.AtomicReference<Process>();
        var finished=new java.util.concurrent.CountDownLatch(1);var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        try(var runtime=new SupervisedPdfPageRuntime(()->{var child=new ProcessBuilder("/bin/sleep","30").start();process.set(child);return new ObservedProcess(child,started);},store(),"synthetic-v1","a".repeat(64));
                var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var task=tasks.submit(()->{try{return runtime.render(new byte[]{1},1);}finally{
                interrupted.set(Thread.currentThread().isInterrupted());finished.countDown();
            }});assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            task.cancel(true);assertThat(process.get().waitFor(5,TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();assertThat(interrupted).isTrue();
            // close acquires the same capacity after the cancelled render's cleanup.
            runtime.close();noAttemptRemains();
        }
    }
    /** Signal protocol entry AFTER durable attempt recording, not process start. */
    private static final class ObservedProcess extends Process {
        private final Process delegate;private final java.util.concurrent.CountDownLatch entered;
        ObservedProcess(Process delegate,java.util.concurrent.CountDownLatch entered){this.delegate=delegate;this.entered=entered;}
        @Override public java.io.OutputStream getOutputStream(){entered.countDown();return delegate.getOutputStream();}
        @Override public java.io.InputStream getInputStream(){return delegate.getInputStream();}
        @Override public java.io.InputStream getErrorStream(){return delegate.getErrorStream();}
        @Override public long pid(){return delegate.pid();}
        @Override public ProcessHandle toHandle(){return delegate.toHandle();}
        @Override public boolean isAlive(){return delegate.isAlive();}
        @Override public int waitFor()throws InterruptedException{return delegate.waitFor();}
        @Override public boolean waitFor(long time,TimeUnit unit)throws InterruptedException{return delegate.waitFor(time,unit);}
        @Override public int exitValue(){return delegate.exitValue();}
        @Override public void destroy(){delegate.destroy();}
        @Override public Process destroyForcibly(){delegate.destroyForcibly();return this;}
    }
    @Test void writableOperatorFilesCannotEnableLinuxProductionCustody()throws Exception {
        if(unsupported())return;
        var helper=Files.writeString(root.resolve("helper"),"synthetic");
        Path runtime=Files.createDirectory(root.resolve("runtime")),artifacts=Files.createDirectory(root.resolve("artifacts"));
        assertThatThrownBy(()->PdfLinuxDeploymentCustody.require(helper,runtime,artifacts)).isInstanceOf(java.io.IOException.class);
    }
}
