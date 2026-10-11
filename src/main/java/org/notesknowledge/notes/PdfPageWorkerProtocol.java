package org.notesknowledge.notes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild;
import org.notesknowledge.websupport.ApiFailureException;

/** Bounded transport AFTER a qualified runtime has started its isolated worker.
 * This class cannot launch an uncontained process or turn configuration into qualification.
 * The runtime still owns OS tree-kill, filesystem/network isolation and orphan cleanup.
 */
final class PdfPageWorkerProtocol {
    private PdfPageWorkerProtocol(){ }
    static PdfChild exchange(Process process,byte[] original,int page,String version,String artifact) {
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=tasks.submit(()->{
                try(var input=new DataInputStream(process.getInputStream());var output=new DataOutputStream(process.getOutputStream())) {
                    if(original==null||original.length<1||original.length>10*1024*1024||page<1||page>100)throw new IOException("Protocol bounds");
                    output.writeInt(0x4e4b5731);output.writeInt(page);output.writeInt(original.length);output.write(original);output.close();
                    if(input.readInt()!=0x4e4b5731||input.readInt()!=page)throw new IOException("Protocol identity");
                    int size=input.readInt();
                    if(size<45||size>PdfPageRasterWorker.MAX_PNG_BYTES)throw new IOException("Protocol bounds");
                    byte[] png=input.readNBytes(size);
                    try {
                        if(png.length!=size||input.read()!=-1)throw new IOException("Protocol framing");
                        requireRgbPng(png);
                        return new PdfChild(page,png,PdfPageRasterWorker.PROFILE,version,artifact,digest(PdfPageRasterWorker.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } finally {java.util.Arrays.fill(png,(byte)0);}
                }
            });
            try {
                var child=result.get(15,TimeUnit.SECONDS);
                if(!process.waitFor(1,TimeUnit.SECONDS)||process.exitValue()!=0){child.close();throw unavailable();}
                return child;
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw unavailable();
            } catch(Exception failure) {throw unavailable();}
            finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // Closing pipes also releases a blocked writer/reader before executor close.
                try{process.getOutputStream().close();}catch(IOException ignored){ }
                try{process.getInputStream().close();}catch(IOException ignored){ }
                try{process.getErrorStream().close();}catch(IOException ignored){ }
                result.cancel(true);
            }
        }
    }
    static String digest(byte[] bytes) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    static void requireRgbPng(byte[] bytes)throws IOException {
        var data=java.nio.ByteBuffer.wrap(bytes);
        if(data.remaining()<45||data.getLong()!=0x89504e470d0a1a0aL)throw new IOException("Raster format");
        boolean header=false,pixels=false,end=false;
        while(data.hasRemaining()) {
            if(data.remaining()<12)throw new IOException("Raster framing");
            int length=data.getInt(),typeStart=data.position(),type=data.getInt();
            if(length<0||length>data.remaining()-4)throw new IOException("Raster bounds");
            int payload=data.position();
            if(!header) {
                if(type!=0x49484452||length!=13)throw new IOException("Raster header");
                int width=data.getInt(),height=data.getInt();
                if(width<1||height<1||width>2048||height>2048||data.get()!=8||data.get()!=2
                        ||data.get()!=0||data.get()!=0||data.get()!=0)throw new IOException("Raster dimensions");
                header=true;
            } else if(type==0x49444154&&!end) {if(length==0)throw new IOException("Raster pixels");pixels=true;}
            else if(type==0x49454e44&&pixels&&length==0&&!end)end=true;
            else throw new IOException("Unapproved raster chunk");
            data.position(payload+length);
            var crc=new java.util.zip.CRC32();crc.update(bytes,typeStart,4+length);
            if(Integer.toUnsignedLong(data.getInt())!=crc.getValue()||end&&data.hasRemaining())throw new IOException("Raster integrity");
        }
        if(!header||!pixels||!end)throw new IOException("Incomplete raster");
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
