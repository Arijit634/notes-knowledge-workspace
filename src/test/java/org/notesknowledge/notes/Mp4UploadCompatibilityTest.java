package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY")
class Mp4UploadCompatibilityTest {
    @TempDir Path directory;
    private byte[] common()throws Exception{return getClass().getResourceAsStream("/attachments/synthetic-common-avc.mp4").readAllBytes();}
    private StrictMp4StructureValidator.Video upload(byte[] bytes)throws Exception{return new StrictMp4StructureValidator().validateUpload(Files.write(directory.resolve("input.mp4"),bytes));}
    @Test void realEncodedIsomMainProfileUploadAgreesWithForkMetadata()throws Exception {
        var bytes=common();var result=upload(bytes);assertThat(result.width()).isEqualTo(128);assertThat(result.durationSeconds()).isEqualTo(1.0);
        try(var validator=new AttachmentMediaValidator()){var accepted=validator.validate(directory.resolve("input.mp4"),"fictional.mp4","video/mp4");assertThat(accepted.width()).isEqualTo(128);assertThat(accepted.height()).isEqualTo(96);assertThat(accepted.durationSeconds()).isEqualTo(1.0);}
        // Upload compatibility must not relax the canonical native-child contract.
        assertThatThrownBy(()->new StrictMp4StructureValidator().validate(directory.resolve("input.mp4"))).isInstanceOf(ApiFailureException.class);
    }
    @Test void appendedGarbageForeignSampleOffsetsAndUnsupportedCodecRemainRejected()throws Exception {
        for(int count=1;count<=7;count++)rejected(Arrays.copyOf(common(),common().length+count));
        byte[] foreign=common();ByteBuffer.wrap(foreign).putInt(find(foreign,"stco")+16,0);rejected(foreign);
        byte[] codec=common();System.arraycopy("hvc1".getBytes(StandardCharsets.US_ASCII),0,codec,find(codec,"avc1")+4,4);rejected(codec);
        byte[] fragment=common();System.arraycopy("moof".getBytes(StandardCharsets.US_ASCII),0,fragment,find(fragment,"moov")+4,4);rejected(fragment);
    }
    @Test void optionalAacTrackIsBoundedAndCannotReferenceForeignOrOverlappingBytes()throws Exception {
        byte[] bytes=withAac();assertThat(upload(bytes).durationSeconds()).isEqualTo(1);
        byte[] bad=bytes.clone();int last=last(bad,"stco");ByteBuffer.wrap(bad).putInt(last+16,0);rejected(bad);
        byte[] unsupported=bytes.clone();int esds=find(unsupported,"esds");unsupported[esds+13]=0x40; // ES flags must be zero, not external URL flags.
        rejected(unsupported);
        byte[] duplicate=bytes.clone();int second=last(duplicate,"tkhd");ByteBuffer.wrap(duplicate).putInt(second+20,1);rejected(duplicate);
    }
    private byte[] withAac()throws Exception {
        // Container/framing fixture only: the AAC payload is not a decoder or playback oracle.
        byte[] original=AttachmentParserPreflightTest.supportedMp4();int movie=find(original,"moov");
        byte[] prefix=Arrays.copyOf(original,movie);int offset=prefix.length+8;
        byte[] header=new byte[84];var h=ByteBuffer.wrap(header);h.putInt(7).position(12);h.putInt(2).position(20);h.putInt(1000).position(40);h.putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x40000000);
        byte[] mdhd=ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(48000).putInt(48000).putInt(0).array();
        byte[] handler=ByteBuffer.allocate(24).putInt(0).putInt(0).put("soun".getBytes(StandardCharsets.US_ASCII)).array();
        byte[] audio=ByteBuffer.allocate(28).putInt(0).putShort((short)0).putShort((short)1).putInt(0).putInt(0).putShort((short)2).putShort((short)16).putInt(0).putInt(48000<<16).array();
        byte[] descriptor={3,25,0,2,0,4,17,64,21,0,0,0,0,0,0,0,0,0,0,0,5,2,0x11,(byte)0x90,6,1,2};
        byte[] sampleEntry=box("mp4a",join(audio,box("esds",join(ints(0),descriptor))));
        byte[] table=box("stbl",join(box("stsd",join(ints(0,1),sampleEntry)),box("stts",ints(0,1,1,48000)),box("stsc",ints(0,1,1,1,1)),box("stsz",ints(0,0,1,4)),box("stco",ints(0,1,offset))));
        byte[] dinf=box("dinf",box("dref",join(ints(0,1),box("url ",ints(1)))));
        byte[] track=box("trak",join(box("tkhd",header),box("mdia",join(box("mdhd",mdhd),box("hdlr",handler),box("minf",join(box("smhd",ints(0,0)),dinf,table))))));
        byte[] oldMovie=Arrays.copyOfRange(original,movie+8,original.length);return join(prefix,box("mdat",new byte[]{1,2,3,4}),box("moov",join(oldMovie,track)));
    }
    @Test void editorExportLayoutAcceptsOnlyBoundedExtendedMediaAndKnownInertMetadata()throws Exception {
        byte[] bytes=editorExport();assertThat(upload(bytes).durationSeconds()).isEqualTo(1);
        byte[] unknown=bytes.clone();unknown[find(unknown,"uuid")+8]^=1;rejected(unknown);
        byte[] outside=bytes.clone();ByteBuffer.wrap(outside).putInt(find(outside,"mdat")+8,1);rejected(outside);
        byte[] truncated=bytes.clone();ByteBuffer.wrap(truncated).putInt(find(truncated,"mdat")+12,truncated.length+1);rejected(truncated);
        byte[] esFlags=bytes.clone();esFlags[find(esFlags,"esds")+16]=(byte)0x80;rejected(esFlags);
        byte[] externalHandler=bytes.clone();int handler=find(externalHandler,"alis");System.arraycopy("url ".getBytes(StandardCharsets.US_ASCII),0,externalHandler,handler+4,4);rejected(externalHandler);
        assertThatThrownBy(()->new StrictMp4StructureValidator().validate(directory.resolve("input.mp4"))).isInstanceOf(ApiFailureException.class);
    }
    @Test void editorExportStillRequiresIndependentForkMetadataAgreement()throws Exception {
        Files.write(directory.resolve("editor.mp4"),editorExport());
        try(var validator=new AttachmentMediaValidator()){
            var accepted=validator.validate(directory.resolve("editor.mp4"),"fictional-editor.mp4","video/mp4");assertThat(accepted.durationSeconds()).isEqualTo(1);assertThat(accepted.mediaType()).isEqualTo("video/mp4");
        }
    }
    @Test void fractionalContainerTimingAgreesWithRoundedForkMetadataWithoutLosingPrecision()throws Exception {
        byte[] bytes=AttachmentParserPreflightTest.supportedMp4();
        for(String type:new String[]{"mvhd","mdhd"})ByteBuffer.wrap(bytes).putInt(find(bytes,type)+24,1003);
        ByteBuffer.wrap(bytes).putInt(find(bytes,"tkhd")+28,1003);ByteBuffer.wrap(bytes).putInt(find(bytes,"stts")+20,1003);
        var path=Files.write(directory.resolve("fractional.mp4"),bytes);
        try(var validator=new AttachmentMediaValidator()){assertThat(validator.validate(path,"fractional.mp4","video/mp4").durationSeconds()).isEqualTo(1.003);}
    }
    private byte[] editorExport()throws Exception {
        byte[] original=withAac();int movie=find(original,"moov");
        byte[] before=Arrays.copyOf(original,movie),moov=rewriteEditorBoxes(Arrays.copyOfRange(original,movie,original.length));
        int firstMedia=find(before,"mdat"),length=ByteBuffer.wrap(before).getInt(firstMedia);
        byte[] extended=join(Arrays.copyOf(before,firstMedia),ByteBuffer.allocate(16).putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII)).putLong(length+8L).array(),Arrays.copyOfRange(before,firstMedia+8,before.length));
        byte[] uuid=box("uuid",join(java.util.HexFormat.of().parseHex("be7acfcb97a942e89c71999491e3afac"),"synthetic inert metadata".getBytes(StandardCharsets.US_ASCII)));
        int ftyp=ByteBuffer.wrap(extended).getInt(0);byte[] result=join(Arrays.copyOf(extended,ftyp),uuid,Arrays.copyOfRange(extended,ftyp,extended.length),moov);
        for(int i=4;i<result.length-12;i++)if(Arrays.equals(result,i,i+4,"stco".getBytes(StandardCharsets.US_ASCII),0,4)){
            int count=ByteBuffer.wrap(result).getInt(i+8);for(int j=0;j<count;j++){int at=i+12+j*4;ByteBuffer.wrap(result).putInt(at,ByteBuffer.wrap(result).getInt(at)+8+uuid.length);}
        }
        return result;
    }
    private static byte[] rewriteEditorBoxes(byte[] input)throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        for(int at=0;at<input.length;){int size=ByteBuffer.wrap(input).getInt(at);String kind=new String(input,at+4,4,StandardCharsets.US_ASCII);byte[] payload=Arrays.copyOfRange(input,at+8,at+size);
            if(java.util.Set.of("moov","trak","mdia","minf","stbl").contains(kind))payload=rewriteEditorBoxes(payload);
            if(kind.equals("minf"))payload=join(payload,box("hdlr",ByteBuffer.allocate(24).putInt(0).putInt(0).put("alis".getBytes(StandardCharsets.US_ASCII)).array()));
            if(kind.equals("tkhd"))ByteBuffer.wrap(payload).putInt(1);
            // The ES stream-priority bits are legal; optional dependency/URL/OCR flags remain forbidden.
            if(kind.equals("stsd")){for(int i=4;i<payload.length-20;i++)if(Arrays.equals(payload,i,i+4,"esds".getBytes(StandardCharsets.US_ASCII),0,4))payload[i+12]=16;}
            output.write(box(kind,payload));at+=size;
        }
        return output.toByteArray();
    }
    private void rejected(byte[] bytes){assertThatThrownBy(()->upload(bytes)).isInstanceOf(ApiFailureException.class);}
    private static int find(byte[] b,String code){return index(b,code,false);}
    private static int last(byte[] b,String code){return index(b,code,true);}
    private static int index(byte[] b,String code,boolean last){byte[] v=code.getBytes(StandardCharsets.US_ASCII);int found=-1;for(int i=4;i<b.length-3;i++)if(Arrays.equals(b,i,i+4,v,0,4)){found=i-4;if(!last)return found;}if(found<0)throw new AssertionError("Fixture box absent");return found;}
    private static byte[] ints(int...v){var b=ByteBuffer.allocate(v.length*4);for(int i:v)b.putInt(i);return b.array();}
    private static byte[] box(String type,byte[] payload){return ByteBuffer.allocate(8+payload.length).putInt(8+payload.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(payload).array();}
    private static byte[] join(byte[]...v)throws Exception{var out=new java.io.ByteArrayOutputStream();for(var b:v)out.write(b);return out.toByteArray();}
}
