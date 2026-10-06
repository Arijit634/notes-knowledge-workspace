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
        /** One-shot bounded private stream, opened/consumed/closed outside database transactions. */
        InputStream open();
    }
    record PdfPage(int page,String text) {
        public PdfPage {if(page<1||page>100||text==null||text.length()>65536)throw new IllegalArgumentException("Invalid PDF page");}
        @Override public String toString(){return "PdfPage[private text omitted]";}
    }
}
