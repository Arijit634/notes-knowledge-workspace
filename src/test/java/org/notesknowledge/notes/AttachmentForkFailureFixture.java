package org.notesknowledge.notes;

import java.io.IOException;
import java.util.Set;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;

/** Test child only: deterministic resource faults, never in the production parser registry/JAR. */
public final class AttachmentForkFailureFixture extends AbstractParser {
    @Override public Set<MediaType> getSupportedTypes(ParseContext context) {
        return Set.of(MediaType.application("pdf"));
    }

    @Override public void parse(TikaInputStream input, ContentHandler handler, Metadata metadata,
            ParseContext context) throws IOException, org.xml.sax.SAXException {
        if (!Boolean.getBoolean("nkw.test.parser.child")) throw new IOException("Test child required");
        input.readNBytes(9); // Synthetic %PDF-1.4 followed by newline.
        switch (input.read()) {
            case 1 -> {
                // The Tika timeout owns cancellation; no Future.cancel or flaky test sleep.
                for (;;) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L);
            }
            case 2 -> Runtime.getRuntime().halt(37);
            case 3 -> {
                byte[] impossible = new byte[Integer.MAX_VALUE - 8];
                metadata.set("fixture", Integer.toString(impossible.length));
            }
            case 4 -> metadata.set("fixture", "x".repeat(131_072));
            case 5 -> handler.characters("x".repeat(131_072).toCharArray(), 0, 131_072);
            case 6 -> metadata.set("fixture-process", Long.toString(ProcessHandle.current().pid()));
            default -> metadata.set("Content-Type", "application/pdf");
        }
    }
}
