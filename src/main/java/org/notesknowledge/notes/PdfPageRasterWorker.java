package org.notesknowledge.notes;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Fixed one-page worker protocol. Run ONLY inside a separately qualified isolated process.
 * Neither Spring nor an ordinary uncontained ProcessBuilder invokes this entry point.
 * The input PDF never crosses back; the output is fresh RGB PNG with no source metadata.
 */
public final class PdfPageRasterWorker {
    public static final String PROFILE="pdf-page-rgb144-cap2048-v1";
    public static final String CONFIGURATION="nkw-pdf-raster-v1;rgb;dpi=144;edge=2048;minDpi=72;annotations=none;metadata=none;pngMax=4194304";
    public static final int MAX_PNG_BYTES=4_194_304;
    private PdfPageRasterWorker(){ }

    public static void main(String[] arguments) {
        // No input-selected path, argument, class or environment configuration. Diagnostics
        // go nowhere: an error is only a nonzero exit, never a PDF-controlled stack/message.
        var protocolOutput=System.out;
        // Libraries can initialize a console logger lazily while opening the PDF. Keep
        // their output off both the private protocol and stderr even without an external
        // logging configuration (including a minimal jlink deployment).
        var discard=new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        System.setOut(discard);
        System.setErr(discard);
        byte[] original=null;
        try {
            if(arguments.length!=0)throw new IOException("Invalid worker protocol");
            var input=new DataInputStream(System.in);
            if(input.readInt()!=0x4e4b5731)throw new IOException("Invalid worker protocol");
            int page=input.readInt(),length=input.readInt();
            if(page<1||page>100||length<1||length>10*1024*1024)throw new IOException("Invalid worker bounds");
            original=input.readNBytes(length);
            if(original.length!=length||input.read()!=-1)throw new IOException("Invalid worker framing");
            byte[] png=render(original,page);
            try {
                var output=new DataOutputStream(protocolOutput);
                output.writeInt(0x4e4b5731);output.writeInt(page);output.writeInt(png.length);output.write(png);output.flush();
            } finally {java.util.Arrays.fill(png,(byte)0);}
        } catch(Exception failure) {System.exit(2);}
        finally {if(original!=null)java.util.Arrays.fill(original,(byte)0);}
    }

    private static byte[] render(byte[] original,int ordinal)throws IOException {
        try(var document=Loader.loadPDF(original)) {
            if(document.isEncrypted()||document.getNumberOfPages()<1||document.getNumberOfPages()>100
                    ||ordinal>document.getNumberOfPages())throw new IOException("Unsupported PDF");
            var page=document.getPage(ordinal-1);var crop=page.getCropBox();
            float width=crop.getWidth(),height=crop.getHeight();
            if(!Float.isFinite(width)||!Float.isFinite(height)||width<1||height<1
                    ||width>2048||height>2048||page.getUserUnit()!=1||page.getRotation()%90!=0)
                throw new IOException("Unsupported page dimensions");
            float scale=Math.min(2f,2048f/Math.max(width,height));
            var renderer=new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            renderer.setAnnotationsFilter(annotation->false);
            BufferedImage image=renderer.renderImage(ordinal-1,scale,ImageType.RGB);
            try {
                if(image.getWidth()<1||image.getHeight()<1||image.getWidth()>2048||image.getHeight()>2048
                        ||(long)image.getWidth()*image.getHeight()>4_194_304)throw new IOException("Raster limit");
                // ImageIO's disk cache is disabled in this isolated worker; no page/source
                // object, action, font program, attachment, metadata or unrelated page is copied.
                ImageIO.setUseCache(false);
                var writers=ImageIO.getImageWritersByFormatName("png");
                if(!writers.hasNext())throw new IOException("PNG encoder unavailable");
                var writer=writers.next();
                var bytes=new BoundedPng();
                try(var output=new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(output);writer.write(null,new IIOImage(image,null,null),writer.getDefaultWriteParam());
                } finally {writer.dispose();}
                return bytes.toByteArray();
            } finally {image.flush();}
        }
    }

    private static final class BoundedPng extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if(count>=MAX_PNG_BYTES)throw new IllegalStateException("Raster output limit");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes,int offset,int length) {
            if(length>MAX_PNG_BYTES-count)throw new IllegalStateException("Raster output limit");
            super.write(bytes,offset,length);
        }
    }
}
