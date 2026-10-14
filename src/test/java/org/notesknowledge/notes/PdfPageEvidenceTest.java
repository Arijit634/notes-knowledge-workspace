package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfPage;

/** Input parsing is exercised through the actual external child, not in the test JVM. */
@Tag("FAST") @Tag("SECURITY")
class PdfPageEvidenceTest {
    @ParameterizedTest @ValueSource(strings={"absent","empty","whitespace","comments","scan","vector","form",
            "annotation","text","mixed","unknown-page","unknown-catalog","unknown-parent","oversized","operator","empty-array","too-many-streams","decode-parameters","unknown-stream"})
    void onlyProvablyEmptyPagesReceiveEmptyEvidence(String kind) throws Exception {
        byte[] bytes;
        try(var document=new PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            var page=new PDPage();document.addPage(page);
            if(java.util.Set.of("empty","whitespace","comments","oversized","operator","decode-parameters","unknown-stream").contains(kind)) {
                var stream=new org.apache.pdfbox.pdmodel.common.PDStream(document);
                String value=switch(kind) {
                    case "empty" -> "";
                    case "whitespace" -> " \t\r\n\f\0";
                    case "comments" -> "% synthetic no operators\n % end";
                    case "oversized" -> "%"+"x".repeat(70_000);
                    case "decode-parameters","unknown-stream" -> " \n";
                    default -> "q Q";
                };
                try(var writer=stream.createOutputStream(COSName.FLATE_DECODE)){writer.write(value.getBytes(StandardCharsets.US_ASCII));}
                if(kind.equals("decode-parameters"))stream.getCOSObject().setItem(COSName.DECODE_PARMS,new COSDictionary());
                if(kind.equals("unknown-stream"))stream.getCOSObject().setInt(COSName.getPDFName("DL"),1);
                page.setContents(stream);
            } else if(java.util.Set.of("scan","vector","form","text","mixed").contains(kind)) {
                try(var content=new PDPageContentStream(document,page)) {
                    if(kind.equals("scan"))content.drawImage(LosslessFactory.createFromImage(document,new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB)),0,0);
                    if(kind.equals("vector")||kind.equals("mixed")){content.addRect(10,10,20,20);content.fill();}
                    if(kind.equals("text")||kind.equals("mixed")){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.showText("Synthetic visible text");content.endText();}
                    if(kind.equals("form")) {
                        var form=new PDFormXObject(document);form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(20,20));
                        form.setResources(new PDResources());
                        try(var writer=form.getContentStream().createOutputStream()){writer.write("0 0 10 10 re f".getBytes(StandardCharsets.US_ASCII));}
                        content.drawForm(form);
                    }
                }
            } else if(kind.equals("empty-array")||kind.equals("too-many-streams")) {
                var streams=new COSArray();
                for(int i=0;i<(kind.equals("empty-array")?2:65);i++)streams.add(new org.apache.pdfbox.pdmodel.common.PDStream(document));
                page.getCOSObject().setItem(COSName.CONTENTS,streams);
            } else if(kind.equals("unknown-parent")) {
                ((COSDictionary)page.getCOSObject().getDictionaryObject(COSName.PARENT)).setBoolean(COSName.getPDFName("UnqualifiedExtension"),true);
            } else if(kind.equals("annotation"))page.getAnnotations().add(new PDAnnotationText());
            else if(kind.equals("unknown-page"))page.getCOSObject().setBoolean(COSName.getPDFName("UnqualifiedExtension"),true);
            else if(kind.equals("unknown-catalog"))document.getDocumentCatalog().getCOSObject().setBoolean(COSName.getPDFName("UnqualifiedExtension"),true);
            document.save(output);bytes=output.toByteArray();
        }
        var pages=PrivatePdfTextExtractor.extract(bytes);
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().provenEmpty()).isEqualTo(java.util.Set.of("absent","empty","whitespace","comments","empty-array").contains(kind));
        if(kind.equals("text")||kind.equals("mixed"))assertThat(pages.getFirst().text()).contains("Synthetic visible text");
        if(pages.getFirst().provenEmpty())assertThat(pages.getFirst().text()).isBlank();
    }

    @Test void mixedDocumentRetainsExactOriginalPageOrdinals() throws Exception {
        byte[] bytes;
        try(var document=new PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            document.addPage(new PDPage());var painted=new PDPage();document.addPage(painted);
            try(var content=new PDPageContentStream(document,painted)){content.addRect(0,0,10,10);content.fill();}
            document.addPage(new PDPage());document.save(output);bytes=output.toByteArray();
        }
        var pages=PrivatePdfTextExtractor.extract(bytes);
        assertThat(pages).extracting(PdfPage::page).containsExactly(1,2,3);
        assertThat(pages).extracting(PdfPage::provenEmpty).containsExactly(true,false,true);
    }

    @Test void missingEvidenceDefaultsUnknownAndCannotContradictNativeText() {
        assertThat(new PdfPage(1,"" ).provenEmpty()).isFalse();
        assertThatThrownBy(()->new PdfPage(1,"visible",true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PdfPage(1,"secret fixture").toString()).doesNotContain("secret fixture");
    }

    @Test void malformedPdfCannotAcquireEmptyEvidence() {
        assertThatThrownBy(()->PrivatePdfTextExtractor.extract("%PDF-1.7\ntruncated".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class).hasNoCause();
    }

    @Test void pageCountAboveEvidenceBudgetIsSafelyRejected() throws Exception {
        byte[] bytes;
        try(var document=new PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            for(int i=0;i<101;i++)document.addPage(new PDPage());
            document.save(output);bytes=output.toByteArray();
        }
        assertThatThrownBy(()->PrivatePdfTextExtractor.extract(bytes))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class).hasNoCause();
    }
}
