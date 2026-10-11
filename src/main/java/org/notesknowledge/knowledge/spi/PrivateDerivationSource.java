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
        default List<PdfPage> pdfPages(byte[] media){return List.of();}
        /** Selected-page transformation needs a separately qualified process runtime.
         * Absence must fail, never fall back to sending the original document. */
        default PdfChild pdfChild(byte[] original,int page) {
            throw org.notesknowledge.websupport.ApiFailureException.of(
                org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        /** One-shot bounded private stream, opened/consumed/closed outside database transactions. */
        InputStream open();
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
