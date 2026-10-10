package org.notesknowledge.notes;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.mock.web.MockMultipartFile;

/** Generated entirely offline from fictional content. No downloaded or personal media. */
public final class QualityMediaFixtures {
    public static MockMultipartFile pdf(int pages,boolean scan,boolean mixed) throws Exception {
        if(pages<1||pages>18)throw new IllegalArgumentException("Fixture page bound");
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            for(int number=1;number<=pages;number++) {
                var page=new PDPage();document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    if(scan||mixed&&number==pages)content.drawImage(LosslessFactory.createFromImage(document,image(false,true)),40,400,500,250);
                    else {
                        content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,740);
                        content.showText("Fictional community workshop report - section "+number);content.newLineAtOffset(0,-24);
                        content.showText(number==pages?"Equipment reserve recommendation: 740 fictional credits.":"Review reusable materials, maintenance scheduling and volunteer training.");
                        content.newLineAtOffset(0,-24);content.showText("This section concerns a synthetic workshop, not a real organization.");content.endText();
                    }
                }
            }
            document.save(output);return new MockMultipartFile("file","miscellaneous.pdf","application/pdf",output.toByteArray());
        }
    }
    public static MockMultipartFile picture(boolean distractor,boolean diagram) throws Exception {
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image(distractor,diagram),"png",output);
        return new MockMultipartFile("file","capture.png","image/png",output.toByteArray());
    }
    private static BufferedImage image(boolean distractor,boolean diagram) {
        var image=new BufferedImage(600,300,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,600,300);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));
            g.setColor(distractor?Color.GREEN:Color.BLUE);g.fillRect(30,40,100,100);
            g.setColor(Color.RED);g.fillOval(430,40,100,100);g.setColor(Color.BLACK);
            g.drawString(diagram?"Equipment reserve: 740 credits":"ASTER",30,220);
            if(diagram){g.drawLine(140,90,410,90);g.drawLine(410,90,395,80);g.drawLine(410,90,395,100);g.drawString("Inventory to reserve",150,60);}
        }finally{g.dispose();}return image;
    }
    private QualityMediaFixtures(){ }
}
