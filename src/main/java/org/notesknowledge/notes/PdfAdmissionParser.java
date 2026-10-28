package org.notesknowledge.notes;

import java.io.IOException;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;

/** Admission-only parser inside the bounded fork. XMP is not an embedded attachment.
 * Suppress its extraction in memory, without rewriting the uploaded bytes or clearing
 * any limit/error flag. Actual attachments, actions and form checks remain intact. */
public final class PdfAdmissionParser extends PDFParser {
    public PdfAdmissionParser(JsonConfig configuration) { super(configuration); }
    @Override protected PDDocument getPDDocument(TikaInputStream input,String password,
            RandomAccessStreamCache.StreamCacheCreateFunction cache,Metadata metadata,ParseContext context)
            throws IOException,org.apache.tika.exception.EncryptedDocumentException {
        PDDocument document=super.getPDDocument(input,password,cache,metadata,context);
        try {
            if(document.getNumberOfPages()<1||document.getNumberOfPages()>100)throw new IOException("PDF admission limit");
            document.getDocumentCatalog().setMetadata(null);
            for(var page:document.getPages())page.setMetadata(null);
            return document;
        } catch(IOException|RuntimeException failed){document.close();throw failed;}
    }
}
