package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("FAST") @Tag("EVALUATION")
class QualityMediaFixturesTest {
    @ParameterizedTest @ValueSource(ints={1,3,18})
    void searchableReportsHaveRealLaterPageFacts(int pages)throws Exception {
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(QualityMediaFixtures.pdf(pages,false,false).getBytes())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(pages);
            var reader=new org.apache.pdfbox.text.PDFTextStripper();reader.setStartPage(pages);reader.setEndPage(pages);
            assertThat(reader.getText(pdf)).contains("740 fictional credits");
            if(pages>1){reader.setStartPage(1);reader.setEndPage(pages-1);assertThat(reader.getText(pdf)).doesNotContain("740 fictional credits");}
        }
    }
    @Test void scansAndMixedDocumentsCannotMasqueradeAsCompleteTextExtraction()throws Exception {
        try(var scan=org.apache.pdfbox.Loader.loadPDF(QualityMediaFixtures.pdf(1,true,false).getBytes());
            var mixed=org.apache.pdfbox.Loader.loadPDF(QualityMediaFixtures.pdf(3,false,true).getBytes())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(scan)).isBlank();
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(mixed)).doesNotContain("740");
            assertThat(mixed.getPage(2).getResources().getXObjectNames()).isNotEmpty();
        }
    }
    @Test void similarImagesHaveDifferentVisibleEvidenceNotDifferentFilenames()throws Exception {
        var a=QualityMediaFixtures.picture(false,false);var b=QualityMediaFixtures.picture(true,false);
        assertThat(a.getOriginalFilename()).isEqualTo(b.getOriginalFilename());assertThat(a.getBytes()).isNotEqualTo(b.getBytes());
        assertThat(javax.imageio.ImageIO.read(a.getInputStream()).getRGB(60,60)).isNotEqualTo(javax.imageio.ImageIO.read(b.getInputStream()).getRGB(60,60));
        assertThat(QualityMediaFixtures.picture(false,true).getBytes()).isNotEqualTo(a.getBytes());
    }
    @Test void spokenAudioAndVisualVideoUseExistingValidOfflineBytes()throws Exception {
        var audio=MeaningfulMediaFixtures.media("audio");
        try(var decoded=javax.sound.sampled.AudioSystem.getAudioInputStream(audio.getInputStream())) {
            assertThat(decoded.getFrameLength()).isPositive();assertThat(decoded.getFormat().getSampleSizeInBits()).isEqualTo(16);
        }
        assertThat(MeaningfulMediaFixtures.media("video").getSize()).isGreaterThan(1000);
        // This video is visual-only: never infer spoken coverage or sampled-frame completeness.
    }
}
