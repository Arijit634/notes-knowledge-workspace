package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL")
class PdfPageRasterWorkerTest {
    @Test void protocolRemainsBinaryWithoutExternalLoggingConfiguration()throws Exception {
        // PDFBox's Java-25 unmapper warning uses the default console logger in this
        // subprocess. A deployment must not depend on a separate log configuration
        // file to keep library diagnostics out of the page-byte protocol.
        try(var child=PdfPageRuntimeFixtures.renderWithoutLoggingConfiguration(DerivationMediaFixtures.media("pdf").getBytes(),1)) {
            assertThat(child.page()).isEqualTo(1);
            PdfPageWorkerProtocol.requireRgbPng(child.bytes());
        }
    }
    @Test void actualWorkerExcludesOtherPagesActionsAttachmentsAndMetadata()throws Exception {
        byte[] original;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(var color:java.util.List.of(java.awt.Color.RED,java.awt.Color.BLUE,java.awt.Color.GREEN)) {
                var page=new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(64,64));pdf.addPage(page);
                try(var content=new PDPageContentStream(pdf,page)){content.setNonStrokingColor(color);content.addRect(0,0,64,64);content.fill();}
            }
            pdf.getDocumentInformation().setCustomMetadataValue("private-fixture","synthetic-other-page-metadata");
            var action=new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('synthetic-never-executed')");
            pdf.getDocumentCatalog().setOpenAction(action);
            var names=new PDDocumentNameDictionary(pdf.getDocumentCatalog());var tree=new PDEmbeddedFilesNameTreeNode();
            var file=new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();file.setFile("synthetic.txt");
            file.setEmbeddedFile(new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(pdf,new java.io.ByteArrayInputStream(new byte[]{1,2,3})));
            tree.setNames(java.util.Map.of("synthetic",file));names.setEmbeddedFiles(tree);pdf.getDocumentCatalog().setNames(names);
            pdf.save(out);original=out.toByteArray();
        }
        try(var child=PdfPageRuntimeFixtures.render(original,2)) {
            assertThat(child.page()).isEqualTo(2);assertThat(child.transformFingerprint()).isEqualTo(PdfPageRuntimeFixtures.transformFingerprint());
            var png=child.bytes();PdfPageWorkerProtocol.requireRgbPng(png);
            var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            assertThat(image.getWidth()).isEqualTo(128);assertThat(image.getHeight()).isEqualTo(128);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertThat(image.getRGB(x,y)&0xffffff).isEqualTo(0x0000ff);
            var expected=new java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=expected.createGraphics();g.setColor(java.awt.Color.BLUE);g.fillRect(0,0,128,128);g.dispose();
            var independentlyEncoded=new ByteArrayOutputStream();javax.imageio.ImageIO.write(expected,"png",independentlyEncoded);
            assertThat(png).isEqualTo(independentlyEncoded.toByteArray());
        }
    }
    @Test void validMixedDocumentActuallyRendersTheSelectedScannedPage()throws Exception {
        try(var child=PdfPageRuntimeFixtures.render(MeaningfulMediaFixtures.media("mixed").getBytes(),3)) {
            var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(child.bytes()));
            // Page image is placed at PDF x=40,y=500,width=320,height=192; scale two.
            assertThat(image.getRGB(128,Math.round(2*(792-500-192)+96))&0xffffff).isEqualTo(0x0000ff);
        }
    }
    @Test void invalidPageMalformedPdfAndOversizedPageFailWithoutOutput()throws Exception {
        var bytes=DerivationMediaFixtures.media("pdf").getBytes();
        assertThatThrownBy(()->PdfPageRuntimeFixtures.render(bytes,2)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(()->PdfPageRuntimeFixtures.render(new byte[]{1,2,3},1)).isInstanceOf(AssertionError.class);
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(3000,3000)));pdf.save(out);
            assertThatThrownBy(()->PdfPageRuntimeFixtures.render(out.toByteArray(),1)).isInstanceOf(AssertionError.class);
        }
    }
    @Test void pngProtocolRejectsTamperAndTheDefaultMaterialCannotTransform()throws Exception {
        try(var child=PdfPageRuntimeFixtures.render(DerivationMediaFixtures.media("pdf").getBytes(),1)) {
            var tampered=child.bytes();tampered[35]^=1;
            assertThatThrownBy(()->PdfPageWorkerProtocol.requireRgbPng(tampered)).isInstanceOf(java.io.IOException.class);
        }
        var material=new org.notesknowledge.knowledge.spi.PrivateDerivationSource.Material() {
            public String markdown(){return null;}public java.io.InputStream open(){return java.io.InputStream.nullInputStream();}
        };
        assertThatThrownBy(()->material.pdfChild(new byte[]{1},1)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
}
