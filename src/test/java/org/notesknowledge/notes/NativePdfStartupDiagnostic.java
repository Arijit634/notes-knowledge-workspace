package org.notesknowledge.notes;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Hosted synthetic-only diagnostic. Never emits native text, paths, arguments,
 * media or environment. Not used by a production factory or packaged in its JAR. */
public final class NativePdfStartupDiagnostic {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Native root required");
        Path root=Path.of(args[0]);
        boolean valid=probe(root.resolve("helper"),root);
        if(!valid) {
            probe(Path.of("/qualification/pdf-diagnostic-helper"),root);
            throw new IllegalStateException("Native PDF synthetic startup denied");
        }
    }
    private static boolean probe(Path helper,Path root)throws Exception {
        var builder=new ProcessBuilder(helper.toString(),root.resolve("runtime").toString(),root.resolve("artifacts").toString());
        builder.environment().clear();builder.directory(root.resolve("artifacts").toFile());
        Process child=builder.start();byte[] output=null,error=null,pdf=null;
        try(var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout=tasks.submit(()->child.getInputStream().readNBytes(4_194_369));
            var stderr=tasks.submit(()->child.getErrorStream().readNBytes(8193));
            try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var bytes=new java.io.ByteArrayOutputStream()) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(32,32)));
                document.save(bytes);pdf=bytes.toByteArray();
            }
            try(var input=new java.io.DataOutputStream(child.getOutputStream())) {
                input.writeInt(0x4e4b5731);input.writeInt(1);input.writeInt(pdf.length);input.write(pdf);
            }catch(java.io.IOException earlyExit){ /* classify only bounded synthetic output below */ }
            output=stdout.get(20,TimeUnit.SECONDS);error=stderr.get(20,TimeUnit.SECONDS);
            if(!child.waitFor(1,TimeUnit.SECONDS))throw new IllegalStateException("Native diagnostic timeout");
            boolean valid=child.exitValue()==0&&output.length>=12&&java.nio.ByteBuffer.wrap(output).getInt()==0x4e4b5731;
            String messages=new String(output,java.nio.charset.StandardCharsets.US_ASCII)+new String(error,java.nio.charset.StandardCharsets.US_ASCII);
            String kind=valid?"VALID_PROTOCOL":messages.contains("Could not reserve")||messages.contains("Native memory allocation")?"BOOTSTRAP_MEMORY":
                    messages.contains("SIGILL")?"BOOTSTRAP_SIGILL":messages.contains("SIGSEGV")?"BOOTSTRAP_SIGSEGV":
                    messages.contains("Operation not permitted")?"BOOTSTRAP_OPERATION_DENIED":"NO_VALID_PROTOCOL";
            var boundary=java.util.regex.Pattern.compile("pdf_sandbox_boundary_([0-9]{1,4})_errno_([0-9]{1,4})").matcher(messages);
            String facts=boundary.find()?",\"boundaryLine\":"+boundary.group(1)+",\"errno\":"+boundary.group(2):"";
            System.out.println("{\"syntheticStartup\":\""+kind+"\",\"exit\":"+child.exitValue()+",\"stdoutBytes\":"+output.length+",\"stderrBytes\":"+error.length+facts+"}");
            return valid;
        }finally {
            child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("Native diagnostic termination uncertain");
            for(byte[] bytes:new byte[][]{output,error,pdf})if(bytes!=null)java.util.Arrays.fill(bytes,(byte)0);
            child.getInputStream().close();child.getOutputStream().close();child.getErrorStream().close();
        }
    }
}
