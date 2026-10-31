package org.notesknowledge.knowledge.spi;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Knowledge-owned consumer seam. Metadata is not authority to read source bytes. */
public interface PrivateDerivationSource {
    record Metadata(PrivateAiSourceCurrentness.Expected expected,String modality,boolean aiEnabled,String lifecycle,
            String mediaType,long sizeBytes,Double durationSeconds,Integer pages) {
        @Override public String toString() { return "PrivateSourceMetadata[REDACTED]"; }
    }
    record Cursor(UUID noteId,UUID attachmentId) { }
    record Inventory(List<Metadata> sources,Cursor next) { public Inventory { sources=List.copyOf(sources); } }
    /** Short transaction, stable identity page. Includes no body, filename, locator or credentials. */
    Inventory inventory(Cursor after,int limit);
    List<Metadata> noteSources(UUID owner,UUID noteId);
    /** Selected exact current source only, acquired in a short caller-owned transaction. */
    Material acquire(PrivateAiSourceCurrentness.Expected expected);
    interface Material {
        String markdown();
        default String title(){return "";}
        default List<PdfPage> pdfPages(byte[] media){return List.of();}
        /** Selected-page transformation needs a separately qualified process runtime.
         * Absence must fail, never fall back to sending the original document. */
        default PdfChild pdfChild(byte[] original,int page) {
            throw org.notesknowledge.websupport.ApiFailureException.of(
                org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        /** Qualified isolated processing only. Metadata/child values grant no dispatch authority. */
        default VideoPlan videoPlan(byte[] original) {throw unavailableVideo();}
        default VideoChild videoChild(byte[] original,VideoPlan plan,int first,int last) {throw unavailableVideo();}
        default VideoAudioPlan videoAudioPlan(byte[] original) {throw unavailableVideo();}
        default VideoAudioChild videoAudioChild(byte[] original,VideoAudioPlan plan) {throw unavailableVideo();}
        private static org.notesknowledge.websupport.ApiFailureException unavailableVideo() {
            return org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        /** One-shot bounded private stream, opened/consumed/closed outside database transactions. */
        InputStream open();
    }
    record VideoAudioPlan(long timescale,long ticks,boolean hasAudio) {
        public VideoAudioPlan {
            if(timescale<1||timescale>1_000_000||ticks<1||ticks>300*timescale||hasAudio&&ticks*16000/timescale<1)
                throw new IllegalArgumentException("Invalid video audio plan");
        }
        public long samples(){return ticks*16000/timescale;}
        public double durationSeconds(){return (double)ticks/timescale;}
        @Override public String toString(){return "VideoAudioPlan[private timing omitted]";}
    }
    record VideoAudioChild(VideoAudioPlan plan,byte[] bytes,String profile,String implementationVersion,
            String artifactSha256,String configurationSha256) implements AutoCloseable {
        public static final String PROFILE="video-scenes-pcm16k-preserve-time-v2";
        public VideoAudioChild {
            if(plan==null||!plan.hasAudio()||bytes==null||bytes.length!=44+2*plan.samples()||bytes.length>9_600_044
                    ||!PROFILE.equals(profile)||implementationVersion==null||!implementationVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
                    ||artifactSha256==null||!artifactSha256.matches("[0-9a-f]{64}")
                    ||configurationSha256==null||!configurationSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid video audio child");
            var h=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(h.getInt(0)!=0x46464952||h.getInt(4)!=bytes.length-8||h.getInt(8)!=0x45564157||h.getInt(12)!=0x20746d66
                    ||h.getInt(16)!=16||h.getShort(20)!=1||h.getShort(22)!=1||h.getInt(24)!=16000||h.getInt(28)!=32000
                    ||h.getShort(32)!=2||h.getShort(34)!=16||h.getInt(36)!=0x61746164||h.getInt(40)!=bytes.length-44)
                throw new IllegalArgumentException("Invalid video audio child");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
        public String transformFingerprint(){return PdfChild.transformFingerprint(profile,implementationVersion,artifactSha256,configurationSha256);}
        @Override public void close(){java.util.Arrays.fill(bytes,(byte)0);}
        @Override public String toString(){return "VideoAudioChild[private media omitted]";}
    }
    record VideoPlan(long timescale,List<Long> durations) {
        public VideoPlan {
            if(timescale<1||timescale>1_000_000||durations==null||durations.isEmpty()||durations.size()>18_000)
                throw new IllegalArgumentException("Invalid video plan");
            durations=List.copyOf(durations);long total=0;
            for(Long delta:durations){if(delta==null||delta<1||delta>30*timescale)throw new IllegalArgumentException("Invalid video plan");total=Math.addExact(total,delta);}
            if(total>300*timescale||durations.size()*timescale>60*total)throw new IllegalArgumentException("Invalid video plan");
        }
        public long tick(int sample) {
            if(sample<0||sample>durations.size())throw new IllegalArgumentException("Invalid video scope");
            long result=0;for(int i=0;i<sample;i++)result+=durations.get(i);return result;
        }
        /** Half-open frame interval. No frame is omitted or duplicated; VFR remains rational. */
        public int next(int first) {
            if(first<0||first>=durations.size())throw new IllegalArgumentException("Invalid video scope");
            int last=first;long ticks=0;
            while(last<durations.size()&&ticks+durations.get(last)<=20*timescale){ticks+=durations.get(last++);}
            if(last==first)return first+1;
            return last;
        }
        @Override public String toString(){return "VideoPlan[private timing omitted]";}
    }
    record VideoChild(VideoPlan plan,int first,int last,byte[] bytes,String profile,String implementationVersion,
            String artifactSha256,String configurationSha256) implements AutoCloseable {
        public VideoChild {
            if(plan==null||first<0||last<=first||last>plan.durations().size()||plan.tick(last)-plan.tick(first)>30*plan.timescale()
                    ||bytes==null||bytes.length<8||bytes.length>4_194_304||!("video-avc-baseline-preserve-ticks-v1".equals(profile)||VideoAudioChild.PROFILE.equals(profile))
                    ||implementationVersion==null||!implementationVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
                    ||artifactSha256==null||!artifactSha256.matches("[0-9a-f]{64}")
                    ||configurationSha256==null||!configurationSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid video child");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
        public String transformFingerprint(){return PdfChild.transformFingerprint(profile,implementationVersion,artifactSha256,configurationSha256);}
        @Override public void close(){java.util.Arrays.fill(bytes,(byte)0);}
        @Override public String toString(){return "VideoChild[private media omitted]";}
    }
    record PdfChild(int page,byte[] bytes,String profile,String implementationVersion,
            String artifactSha256,String configurationSha256) implements AutoCloseable {
        public PdfChild {
            if(page<1||page>100||bytes==null||bytes.length<8||bytes.length>4_194_304
                    ||!java.util.Arrays.equals(java.util.Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
                    ||!"pdf-page-rgb144-cap2048-v1".equals(profile)
                    ||implementationVersion==null||!implementationVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
                    ||artifactSha256==null||!artifactSha256.matches("[0-9a-f]{64}")
                    ||configurationSha256==null||!configurationSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid PDF child");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
        public String transformFingerprint() {
            return transformFingerprint(profile,implementationVersion,artifactSha256,configurationSha256);
        }
        public static String transformFingerprint(String profile,String version,String artifact,String configuration) {
            try(var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes)) {
                for(String field:List.of("nkw-media-transform-profile-v1",profile,version,artifact,configuration)) {
                    byte[] encoded=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);
                }
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch(java.io.IOException|java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Profile identity unavailable");}
        }
        @Override public void close(){java.util.Arrays.fill(bytes,(byte)0);}
        @Override public String toString(){return "PdfChild[private media omitted]";}
    }
    record PdfPage(int page,String text,boolean provenEmpty) {
        public PdfPage {if(page<1||page>100||text==null||text.length()>65536||provenEmpty&&!text.isBlank())throw new IllegalArgumentException("Invalid PDF page");}
        public PdfPage(int page,String text){this(page,text,false);}
        @Override public String toString(){return "PdfPage[private text omitted]";}
    }
}
