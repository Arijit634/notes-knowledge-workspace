package org.notesknowledge.notes;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.notesknowledge.websupport.ApiFailureException;

/** Approved bounded fork containment, no in-process document parser or local OCR. */
final class PrivatePdfTextExtractor {
    static List<PrivateDerivationSource.PdfPage> extract(byte[] bytes) {
        java.nio.file.Path directory=null;
        try {
            if(bytes.length>AttachmentMediaValidator.PDF_BYTES)throw unavailable();
            directory=AttachmentParserRuntime.privateDirectory();var input=directory.resolve("source.pdf");Files.write(input,bytes);
            String content;
            try(var parser=new AttachmentParserRuntime(true)){content=parser.extractPdfXhtml(input);}
            var factory=javax.xml.stream.XMLInputFactory.newFactory();
            factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            var reader=factory.createXMLStreamReader(new java.io.StringReader(content));
            var pages=new ArrayList<PrivateDerivationSource.PdfPage>();StringBuilder text=null;int depth=0,pageDepth=0,page=0;
            while(reader.hasNext()) {
                int event=reader.next();
                if(event==javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if("div".equals(reader.getLocalName())&&"page".equals(reader.getAttributeValue(null,"class"))) {
                        if(text!=null||++page>100)throw unavailable();text=new StringBuilder();pageDepth=depth;
                    }
                } else if(event==javax.xml.stream.XMLStreamConstants.CHARACTERS&&text!=null) {
                    text.append(reader.getText());if(text.length()>65536)throw unavailable();
                } else if(event==javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    if(text!=null&&depth==pageDepth){pages.add(new PrivateDerivationSource.PdfPage(page,text.toString()));text=null;}
                    else if(text!=null)text.append('\n');depth--;
                }
            }
            reader.close();return List.copyOf(pages);
        } catch(ApiFailureException failure){throw failure;}
        catch(Exception failure){throw unavailable();}
        finally {if(directory!=null)AttachmentParserRuntime.removeDirectory(directory);}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
