package org.notesknowledge.notes;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.mock.web.MockMultipartFile;

/** Frozen fictional content, not a model-quality oracle. No fixture downloads or native encoder. */
public final class MeaningfulMediaFixtures {
    private MeaningfulMediaFixtures() { }
    public static MockMultipartFile media(String kind) throws Exception {
        return switch(kind) {
            case "image" -> file("scene.png","image/png",png(scene()));
            case "audio" -> file("aster.wav","audio/wav",speech());
            case "video" -> file("scene.mp4","video/mp4",video());
            case "pdf", "scan", "mixed", "unreadable" -> file("fictional.pdf","application/pdf",pdf(kind));
            default -> throw new IllegalArgumentException("Unknown fictional fixture");
        };
    }
    private static MockMultipartFile file(String name,String type,byte[] bytes) {return new MockMultipartFile("file",name,type,bytes);}
    public static BufferedImage scene() {
        var image=new BufferedImage(160,96,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {g.setColor(Color.WHITE);g.fillRect(0,0,160,96);g.setColor(Color.BLUE);g.fillRect(8,8,48,48);
            g.setColor(Color.RED);g.fillOval(96,12,40,40);g.setColor(Color.BLACK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.drawString("ASTER",8,82);
        } finally {g.dispose();}return image;
    }
    private static byte[] png(BufferedImage image)throws Exception {var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();}
    private static byte[] speech()throws Exception {
        // Windows System.Speech, fictional sentence only. Frozen PCM makes CI portable/offline.
        // Spoken: The fictional observatory is on planet Aster. Its dome is silver.
        try(var resource=MeaningfulMediaFixtures.class.getResourceAsStream("/knowledge/aster-speech.wav.gz.b64")) {
            if(resource==null)throw new IllegalStateException("Missing fictional speech fixture");
            byte[] compressed=Base64.getMimeDecoder().decode(resource.readAllBytes());
            try(var input=new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                var sound=javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(input.readAllBytes()))) {
                var format=sound.getFormat();
                if(!format.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)||format.getSampleSizeInBits()!=16||format.isBigEndian())
                    throw new IllegalStateException("Unexpected fictional speech encoding");
                byte[] pcm=sound.readAllBytes();int channels=format.getChannels(),rate=(int)format.getSampleRate();
                // System.Speech uses an 18-byte fmt chunk. Normalize custody to the approved
                // canonical PCM WAV form without changing any spoken samples or production gate.
                return ByteBuffer.allocate(44+pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16)
                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm).array();
            }
        }
    }
    private static byte[] pdf(String kind)throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            int count=kind.equals("pdf")||kind.equals("mixed")?3:1;
            for(int n=1;n<=count;n++) {
                var page=new PDPage();pdf.addPage(page);
                try(var content=new PDPageContentStream(pdf,page)) {
                    if(kind.equals("scan")||kind.equals("mixed")&&n==3)content.drawImage(LosslessFactory.createFromImage(pdf,scene()),40,500,320,192);
                    else if(!kind.equals("unreadable")) {
                        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,700);
                        content.showText(n==3?"The fictional archive opens at dawn; its door is violet.":"Fictional archive ledger page "+n+": ordinary supplies and inventory.");content.endText();
                    }
                }
            }
            pdf.save(out);return out.toByteArray();
        }
    }
    /** Real H.264 Baseline I_PCM IDR frame, not the older structural-only dummy NAL fixture.
     * One two-second still scene; NO audio track. The current accepted container gate rejects multi-track video.
     */
    public static byte[] video()throws Exception {
        var sps=new Bits();sps.bits(66,8);sps.bits(0,8);sps.bits(10,8);sps.ue(0);sps.ue(0);sps.ue(2);sps.ue(0);sps.bit(0);
        sps.ue(9);sps.ue(5);sps.bit(1);sps.bit(1);sps.bit(0);sps.bit(0);
        var pps=new Bits();pps.ue(0);pps.ue(0);pps.bit(0);pps.bit(0);pps.ue(0);pps.ue(0);pps.ue(0);pps.bit(0);pps.bits(0,2);pps.se(0);pps.se(0);pps.se(0);pps.bit(1);pps.bit(0);pps.bit(0);
        byte[] sequence=nal(0x67,sps.finish()),picture=nal(0x68,pps.finish());
        var slice=new Bits();slice.ue(0);slice.ue(2);slice.ue(0);slice.bits(0,4);slice.ue(0);slice.bit(0);slice.bit(0);slice.se(0);slice.ue(1);
        var image=scene();
        for(int my=0;my<6;my++)for(int mx=0;mx<10;mx++) {
            slice.ue(25);slice.align();
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)slice.bits(yuv(image.getRGB(mx*16+x,my*16+y),0),8);
            for(int plane=1;plane<=2;plane++)for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int sum=0;for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)sum+=yuv(image.getRGB(mx*16+x*2+dx,my*16+y*2+dy),plane);
                slice.bits(sum/4,8);
            }
        }
        byte[] idr=nal(0x65,slice.finish());byte[] sample=join(ints(idr.length),idr);
        byte[] avc=box("avcC",join(new byte[]{1,66,0,10,(byte)255,(byte)225},shorts(sequence.length),sequence,new byte[]{1},shorts(picture.length),picture));
        byte[] ftyp=box("ftyp","mp42\0\0\0\0mp42".getBytes(StandardCharsets.US_ASCII));
        var movie=ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(2000);
        movie.putInt(0x10000).putShort((short)0x100).position(36);matrix(movie);movie.position(96);movie.putInt(2);
        var track=ByteBuffer.allocate(84).putInt(7).putInt(0).putInt(0).putInt(1).putInt(0).putInt(2000);
        track.position(40);matrix(track);track.putInt(160<<16).putInt(96<<16);
        byte[] mdhd=ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(2000).putShort((short)0x55c4).putShort((short)0).array();
        byte[] hdlr=ByteBuffer.allocate(25).putInt(0).putInt(0).put("vide".getBytes(StandardCharsets.US_ASCII)).array();
        var description=ByteBuffer.allocate(94).putInt(0).putInt(1).putInt(86+avc.length).put("avc1".getBytes(StandardCharsets.US_ASCII));
        description.position(22);description.putShort((short)1);description.position(40);description.putShort((short)160).putShort((short)96).putInt(72<<16).putInt(72<<16).putInt(0).putShort((short)1);
        description.position(90);description.putShort((short)24).putShort((short)-1);
        var table=box("stbl",join(box("stsd",join(description.array(),avc)),box("stts",ints(0,1,1,2000)),box("stsc",ints(0,1,1,1,1)),box("stsz",ints(0,0,1,sample.length)),box("stco",ints(0,1,ftyp.length+8))));
        var dinf=box("dinf",box("dref",join(ints(0,1),box("url ",ints(1)))));
        var mdia=box("mdia",join(box("mdhd",mdhd),box("hdlr",hdlr),box("minf",join(box("vmhd",ints(1,0,0)),dinf,table))));
        return join(ftyp,box("mdat",sample),box("moov",join(box("mvhd",movie.array()),box("trak",join(box("tkhd",track.array()),mdia)))));
    }
    private static void matrix(ByteBuffer b){b.putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x40000000);}
    private static int yuv(int rgb,int plane){int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;return switch(plane){case 0->16+((66*r+129*g+25*b+128)>>8);case 1->128+((-38*r-74*g+112*b+128)>>8);default->128+((112*r-94*g-18*b+128)>>8);};}
    private static byte[] nal(int header,byte[] rbsp){var out=new ByteArrayOutputStream();out.write(header);int zeros=0;for(byte b:rbsp){int v=b&255;if(zeros==2&&v<=3){out.write(3);zeros=0;}out.write(v);zeros=v==0?zeros+1:0;}return out.toByteArray();}
    private static byte[] shorts(int n){return ByteBuffer.allocate(2).putShort((short)n).array();}
    private static byte[] ints(int... n){var b=ByteBuffer.allocate(n.length*4);for(int v:n)b.putInt(v);return b.array();}
    private static byte[] box(String name,byte[] b){return ByteBuffer.allocate(b.length+8).putInt(b.length+8).put(name.getBytes(StandardCharsets.US_ASCII)).put(b).array();}
    private static byte[] join(byte[]... values)throws Exception {var out=new ByteArrayOutputStream();for(byte[] b:values)out.write(b);return out.toByteArray();}
    private static final class Bits {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();int value,count;
        void bit(int b){value=(value<<1)|b;if(++count==8){out.write(value);count=0;value=0;}}
        void bits(int n,int width){for(int i=width-1;i>=0;i--)bit(n>>>i&1);}
        void ue(int n){int v=n+1,width=32-Integer.numberOfLeadingZeros(v);for(int i=1;i<width;i++)bit(0);bits(v,width);}
        void se(int n){ue(n<=0?-2*n:2*n-1);}
        void align(){while(count!=0)bit(0);}
        byte[] finish(){bit(1);align();return out.toByteArray();}
    }
}
