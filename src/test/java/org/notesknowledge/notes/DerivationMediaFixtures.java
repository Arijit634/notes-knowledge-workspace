package org.notesknowledge.notes;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only synthetic byte custody. Never packaged into the application. */
@TestConfiguration(proxyBeanMethods=false)
public class DerivationMediaFixtures {
    @Bean @Primary PrivateAttachmentObjectStore derivationTestStore() {
        return new PrivateAttachmentObjectStore() {
            final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();
            public InputStream openRange(String reference,long offset,long length) {
                outside();var value=bytes.get(reference);if(value==null)throw new IllegalStateException("Synthetic source missing");
                return new ByteArrayInputStream(value,(int)offset,(int)length);
            }
            public void write(String reference,InputStream source,long size) {
                outside();try {var value=source.readNBytes((int)size+1);if(value.length!=size||bytes.putIfAbsent(reference,value)!=null)throw new AssertionError("Synthetic custody violation");}
                catch(java.io.IOException failure){throw new AssertionError("Synthetic fixture read failed");}
            }
            public void delete(String reference){outside();bytes.remove(reference);}
            public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return List.of();}
            private void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Object I/O in transaction");}
        };
    }
    public static MockMultipartFile media(String kind) throws Exception {
        var bytes=new ByteArrayOutputStream();String type,extension;
        switch(kind) {
            case "image" -> {javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,12,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);type="image/png";extension="png";}
            case "audio" -> {bytes.write(AttachmentMediaValidatorTest.wav(16000,1,16000));type="audio/wav";extension="wav";}
            case "video" -> {bytes.write(AttachmentParserPreflightTest.supportedMp4());type="video/mp4";extension="mp4";}
            case "pdf","pdf-text" -> {
                try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
                    var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                    if(kind.equals("pdf-text"))try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                        content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                        content.newLineAtOffset(40,700);content.showText("Synthetic PDF page evidence");content.endText();
                    }
                    pdf.save(bytes);
                }
                type="application/pdf";extension="pdf";
            }
            default -> throw new IllegalArgumentException("Unknown synthetic modality");
        }
        return new MockMultipartFile("file","synthetic."+extension,type,bytes.toByteArray());
    }
}
