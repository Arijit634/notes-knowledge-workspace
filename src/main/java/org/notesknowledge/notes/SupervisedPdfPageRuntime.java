package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild;
import org.notesknowledge.websupport.ApiFailureException;

/** Candidate production transport integration, not automatic OS qualification.
 * Registration still requires qualified platform/deployment preflight. The
 * caller owns original/source authorization; this runtime grants none. Every
 * returned child still requires independent exact-byte dispatch approval.
 */
final class SupervisedPdfPageRuntime implements PdfPageRuntime,AutoCloseable {
    @FunctionalInterface interface Launch {Process start()throws IOException;}
    private final Launch launcher;
    private final PdfWorkerAttemptStore attempts;
    private final String version,artifact;
    private final Semaphore capacity=new Semaphore(1);
    private volatile boolean poisoned,closed;

    static SupervisedPdfPageRuntime linux(Path helper,Path runtime,Path artifacts,
            String identity,Path recoveryDirectory,String version,String artifact)throws IOException {
      try {
        metadata(version,artifact);
        if(!artifact.equals(identity))throw new IOException("PDF runtime configuration unavailable");
        PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);
        PdfLinuxServiceBudget.require();
        PdfRuntimeIdentity.require(identity,runtime,artifacts,helper);
        var candidate=new SupervisedPdfPageRuntime(()->{
            // Recheck custody at every start. No writable deployment input is
            // accepted merely because its fingerprint matched at application boot.
            PdfLinuxDeploymentCustody.require(helper,runtime,artifacts);
            PdfLinuxServiceBudget.require();
            return LinuxPdfProcess.start(helper,runtime,artifacts,identity);
        },new PdfWorkerAttemptStore(recoveryDirectory),version,artifact);
        try {
            // A deterministic, application-owned synthetic document proves that
            // this exact deployment can start, exchange, validate and clean up.
            // It does not substitute for native OS security qualification.
            try(var child=candidate.render(preflightDocument(),1)){
                var png=java.nio.ByteBuffer.wrap(child.bytes());
                if(png.getInt(16)!=64||png.getInt(20)!=64)throw unavailable();
            }
            return candidate;
        }catch(RuntimeException failed){candidate.close();throw new IOException("PDF runtime preflight unavailable");}
      }catch(IOException|RuntimeException failed){throw new IOException("PDF runtime preflight unavailable");}
    }
    SupervisedPdfPageRuntime(Launch launcher,PdfWorkerAttemptStore attempts,String version,String artifact)throws IOException {
        metadata(version,artifact);
        if(launcher==null||attempts==null)throw new IOException("PDF runtime configuration unavailable");
        this.launcher=launcher;this.attempts=attempts;this.version=version;this.artifact=artifact;
    }
    @Override public PdfChild render(byte[] original,int page) {
        if(original==null||original.length<1||original.length>10*1024*1024||page<1||page>100)throw unavailable();
        boolean acquired=false;Process process=null;PdfWorkerAttemptStore.Attempt attempt=null;PdfChild result=null;
        try {
            acquired=capacity.tryAcquire(5,TimeUnit.SECONDS);
            if(!acquired||closed||poisoned)throw unavailable();
            process=launcher.start();
            try{attempt=attempts.record(process);}
            catch(IOException uncertain){poisoned=true;throw uncertain;}
            result=PdfPageWorkerProtocol.exchange(process,original,page,version,artifact);
            // Cleanup must succeed BEFORE evidence is returned to the derivation
            // attempt. A leaked resource is not a successful transformation.
            cleanup(process,attempt);attempt=null;
            return result;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();if(result!=null)result.close();throw unavailable();}
        catch(Exception failure){if(result!=null)result.close();throw unavailable();}
        finally {
            try {
                if(process!=null)cleanup(process,attempt);
            }catch(Exception uncertain){poisoned=true;if(result!=null)result.close();throw unavailable();}
            finally{if(acquired)capacity.release();}
        }
    }
    private static void cleanup(Process process,PdfWorkerAttemptStore.Attempt attempt)throws Exception {
        // Cancellation must not interrupt FileChannel.force during durable
        // cleanup. Observe death first, clear the preserved flag for cleanup,
        // and restore it for the caller; never discard cancellation authority.
        boolean interrupted=Thread.interrupted();
        try {
            terminate(process);
            interrupted|=Thread.interrupted();
            if(attempt!=null)attempt.close();
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static void terminate(Process process)throws Exception {
        boolean interrupted=Thread.interrupted();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            process.destroyForcibly();
            while(process.isAlive()){
                long left=end-System.nanoTime();if(left<=0)throw unavailable();
                try{process.waitFor(left,TimeUnit.NANOSECONDS);}catch(InterruptedException cancelled){interrupted=true;}
            }
            if(process instanceof AutoCloseable cleanup)cleanup.close();
            process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static void metadata(String version,String artifact)throws IOException {
        if(version==null||!version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")||artifact==null||!artifact.matches("[0-9a-f]{64}"))
            throw new IOException("PDF runtime configuration unavailable");
    }
    private static byte[] preflightDocument() {
        // Trusted fixed fixture generation, NOT parsing an uploaded PDF in Spring.
        var output=new java.io.ByteArrayOutputStream();
        var offsets=new java.util.ArrayList<Integer>();
        output.writeBytes("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for(String object:java.util.List.of("<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 32 32] /Resources << >> >>")){
            offsets.add(output.size());
            output.writeBytes((offsets.size()+" 0 obj\n"+object+"\nendobj\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        int xref=output.size();
        output.writeBytes("xref\n0 4\n0000000000 65535 f \n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for(int offset:offsets)output.writeBytes(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offset)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.writeBytes(("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return output.toByteArray();
    }
    @Override public void close()throws IOException {
        boolean acquired=false;
        try {
            acquired=capacity.tryAcquire(35,TimeUnit.SECONDS);
            if(!acquired){poisoned=true;throw new IOException("PDF worker shutdown unavailable");}
            closed=true;attempts.close();
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("PDF worker shutdown unavailable");}
        finally{if(acquired)capacity.release();}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
