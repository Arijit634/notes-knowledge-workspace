package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.metadata.PagedText;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY")
class CompactAttachmentParserTest {
    @TempDir Path directory;

    @Test void compactForkReadsVideoAndMaximumPageCountPdf() throws Exception {
        Path video=Files.write(directory.resolve("synthetic.mp4"),AttachmentParserPreflightTest.supportedMp4());
        Path pdf=pdf(100,false);
        try(var parser=new AttachmentParserRuntime(false,"compact-v1")) {
            assertThat(parser.parse(video).get("Content-Type")).isEqualTo("video/mp4");
            assertThat(parser.parse(pdf).getInt(PagedText.N_PAGES)).isEqualTo(100);
        }
    }

    @Test void compactExtractionPreservesRealPdfText() throws Exception {
        Path pdf=pdf(1,true);
        try(var parser=new AttachmentParserRuntime(true,"compact-v1")) {
            var extracted=parser.extractPdfXhtml(pdf);
            assertThat(extracted.xhtml()).contains("Synthetic parser evidence");
            assertThat(extracted.emptyPages()).hasSize(1);
        }
    }

    @Test void unrecognizedOperatorProfileFailsClosedWithoutAcceptingArguments() {
        assertThatThrownBy(()->AttachmentParserRuntime.memoryArguments("compact-v1 -Xmx4g"))
                .isInstanceOfSatisfying(ApiFailureException.class,
                        e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
    }

    private Path pdf(int pages,boolean text) throws Exception {
        Path path=directory.resolve("synthetic-"+pages+".pdf");
        try(var document=new PDDocument()) {
            for(int i=0;i<pages;i++)document.addPage(new PDPage());
            if(text)try(var stream=new PDPageContentStream(document,document.getPage(0))) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(20,700);stream.showText("Synthetic parser evidence");stream.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
}
