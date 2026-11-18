package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class PcmMeasurementsTest {
    @Test void exactSilenceAndQuietSubsecondToneAreDistinguishedWithoutChangingSamples() {
        byte[] silence=wav(new short[6400],1,16000);
        var empty=GoogleAudioSignals.measurePcm(silence);
        assertThat(empty.allSamplesZero()).isTrue();assertThat(empty.frames()).isEqualTo(6400);
        assertThat(empty.peakAbsolute()).containsExactly(0);assertThat(empty.rms()).containsExactly(0d);
        assertThat(empty.zeroCrossings()).containsExactly(0);
        short[] samples=new short[6400];
        for(int i=0;i<samples.length;i++)samples[i]=(short)(2000*Math.sin(2*Math.PI*440*i/16000));
        byte[] tone=wav(samples,1,16000),original=tone.clone();
        var measured=GoogleAudioSignals.measurePcm(tone);
        assertThat(measured.allSamplesZero()).isFalse();assertThat(measured.sampleRate()).isEqualTo(16000);
        assertThat(measured.peakAbsolute()).containsExactly(2000);
        assertThat(measured.rms().getFirst()).isBetween(1400d,1420d);
        assertThat(measured.zeroCrossings().getFirst()).isBetween(350,353);
        assertThat(tone).isEqualTo(original);assertThat(measured.toString()).isEqualTo("PcmMeasurements[REDACTED]");
    }
    @Test void stereoChannelsAndDcAreMeasuredSeparatelyWithoutInventingOscillation() {
        var measured=GoogleAudioSignals.measurePcm(wav(new short[]{1000,-500,1000,-500,1000,-500,1000,-500},2,8000));
        assertThat(measured.channels()).isEqualTo(2);assertThat(measured.frames()).isEqualTo(4);
        assertThat(measured.peakAbsolute()).containsExactly(1000,500);
        assertThat(measured.rms()).containsExactly(1000d,500d);
        assertThat(measured.zeroCrossings()).containsExactly(0,0);assertThat(measured.allSamplesZero()).isFalse();
        assertThatThrownBy(()->measured.peakAbsolute().set(0,7)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void signedMinimumAndZerosDoNotOverflowOrInventCrossings() {
        var measured=GoogleAudioSignals.measurePcm(wav(new short[]{Short.MIN_VALUE,0,0,Short.MAX_VALUE,0,-1},1,8000));
        assertThat(measured.peakAbsolute()).containsExactly(32768);
        assertThat(measured.zeroCrossings()).containsExactly(2);
        assertThat(measured.rms().getFirst()).isFinite().isBetween(18000d,20000d);
    }
    @Test void boundedUnknownRiffChunksAndOddPaddingNeverBecomeSampleEvidence() {
        byte[] base=wav(new short[8],1,16000);
        byte[] chunk=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put("JUNK".getBytes(StandardCharsets.US_ASCII)).putInt(3).put(new byte[]{1,2,3,0}).array();
        byte[] withChunk=new byte[base.length+chunk.length];
        System.arraycopy(base,0,withChunk,0,36);System.arraycopy(chunk,0,withChunk,36,chunk.length);
        System.arraycopy(base,36,withChunk,36+chunk.length,base.length-36);put32(withChunk,4,withChunk.length-8);
        assertThat(GoogleAudioSignals.measurePcm(withChunk)).isEqualTo(GoogleAudioSignals.measurePcm(base));
    }
    @Test void ambiguousTruncatedUnsupportedAndOverflowingFramesProduceNoMeasurements() {
        byte[] valid=wav(new short[8],1,16000);
        for(int offset:new int[]{4,16,24,28,40}) {
            byte[] changed=valid.clone();put32(changed,offset,-1);
            assertThatCode(()->GoogleAudioSignals.measurePcm(changed)).doesNotThrowAnyException();
            assertThat(GoogleAudioSignals.measurePcm(changed)).isNull();
        }
        for(int length:new int[]{0,12,43,valid.length-1})
            assertThat(GoogleAudioSignals.measurePcm(Arrays.copyOf(valid,length))).isNull();
        byte[] compressed=valid.clone();compressed[20]=3;
        assertThat(GoogleAudioSignals.measurePcm(compressed)).isNull();
        byte[] unaligned=Arrays.copyOf(valid,valid.length-1);put32(unaligned,4,unaligned.length-8);put32(unaligned,40,15);
        assertThat(GoogleAudioSignals.measurePcm(unaligned)).isNull();
        byte[] duplicate=Arrays.copyOf(valid,valid.length+10);
        var end=ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN);end.position(valid.length);
        end.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short)0);put32(duplicate,4,duplicate.length-8);
        assertThat(GoogleAudioSignals.measurePcm(duplicate)).isNull();
        assertThat(GoogleAudioSignals.measurePcm(new byte[10_485_761])).isNull();
    }
    @Test void metadataChunkBudgetIsBoundedEvenWhenEveryLengthIsIndividuallyValid() {
        byte[] base=wav(new short[8],1,16000),many=Arrays.copyOf(base,base.length+2049*8);
        var out=ByteBuffer.wrap(many).order(ByteOrder.LITTLE_ENDIAN);out.position(base.length);
        for(int i=0;i<2049;i++)out.put("JUNK".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        put32(many,4,many.length-8);assertThat(GoogleAudioSignals.measurePcm(many)).isNull();
    }
    static byte[] wav(short[] samples,int channels,int rate) {
        var b=ByteBuffer.allocate(44+samples.length*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity()-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
            .putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2)
            .putShort((short)(channels*2)).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length*2);
        for(short sample:samples)b.putShort(sample);return b.array();
    }
    private static void put32(byte[] b,int at,int value){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(at,value);}
}
