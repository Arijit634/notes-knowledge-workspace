package org.notesknowledge.notes;

import java.io.IOException;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.tika.config.JsonConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;

/** Loaded only by the existing bounded Tika fork. No rendering or transformation. */
public final class PdfEvidenceParser extends PDFParser {
    static final String EMPTY_PAGES = "nkw:pdf-empty-v1";
    private static final Set<String> CATALOG_KEYS=Set.of("Type","Pages","Version","Metadata");
    private static final Set<String> PAGE_KEYS=Set.of("Type","Parent","Resources","MediaBox","CropBox",
            "Rotate","BleedBox","TrimBox","ArtBox","Contents","UserUnit");
    private static final Set<String> PARENT_KEYS=Set.of("Type","Parent","Kids","Count","Resources","MediaBox","CropBox","Rotate");
    private static final Set<String> STREAM_KEYS=Set.of("Length","Filter","DecodeParms");
    public PdfEvidenceParser(JsonConfig configuration) { super(configuration); }

    @Override protected PDDocument getPDDocument(TikaInputStream input,String password,
            RandomAccessStreamCache.StreamCacheCreateFunction cache,Metadata metadata,ParseContext context)
            throws IOException,org.apache.tika.exception.EncryptedDocumentException {
        PDDocument document=super.getPDDocument(input,password,cache,metadata,context);
        try { metadata.set(EMPTY_PAGES,evidence(document));return document; }
        catch(IOException|RuntimeException failure) { document.close();throw failure; }
    }

    static String evidence(PDDocument document) throws IOException {
        int count=document.getNumberOfPages();
        if(count<1||count>100)throw new IOException("PDF evidence limit");
        if(document.isEncrypted()||!keys(document.getDocumentCatalog().getCOSObject(),CATALOG_KEYS))return "U".repeat(count);
        StringBuilder result=new StringBuilder(count);int remaining=1_048_576;
        for(int i=0;i<count;i++) {
            PDPage page=document.getPage(i);COSDictionary dictionary=page.getCOSObject();
            boolean safe=keys(dictionary,PAGE_KEYS)&&COSName.PAGE.equals(dictionary.getCOSName(COSName.TYPE));
            var visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<COSDictionary,Boolean>());
            COSBase parent=dictionary.getDictionaryObject(COSName.PARENT);
            while(safe&&parent!=null) {
                if(!(parent instanceof COSDictionary ancestor)||visited.size()>=32||!visited.add(ancestor)
                        ||!keys(ancestor,PARENT_KEYS)||!COSName.PAGES.equals(ancestor.getCOSName(COSName.TYPE))) {safe=false;break;}
                parent=ancestor.getDictionaryObject(COSName.PARENT);
            }
            COSBase contents=dictionary.getDictionaryObject(COSName.CONTENTS);
            if(safe&&contents!=null) {
                COSArray streams=new COSArray();
                if(contents instanceof COSStream)streams.add(contents);
                else if(contents instanceof COSArray array&&array.size()<=64)streams=array;
                else safe=false;
                int pageBytes=0;
                for(int s=0;safe&&s<streams.size();s++) {
                    if(!(streams.getObject(s) instanceof COSStream stream)){safe=false;break;}
                    int cap=Math.min(65_536-pageBytes,remaining);
                    if(cap<=0){safe=false;break;}
                    byte[] decoded=boundedContent(stream,cap);
                    if(decoded==null){safe=false;remaining=0;break;}
                    pageBytes+=decoded.length;remaining-=decoded.length;
                    // Only whitespace/comments prove no painting operators. q/Q, unknown
                    // operators, images, vectors and form invocations conservatively remain U.
                    boolean comment=false;
                    for(byte b:decoded) {
                        int c=Byte.toUnsignedInt(b);
                        if(comment){if(c==10||c==13)comment=false;continue;}
                        if(c==37){comment=true;continue;}
                        if(c!=0&&c!=9&&c!=10&&c!=12&&c!=13&&c!=32){safe=false;break;}
                    }
                }
            }
            result.append(safe?'E':'U');
        }
        return result.toString();
    }
    private static boolean keys(COSDictionary dictionary,Set<String> allowed) {
        return dictionary.keySet().stream().allMatch(key->allowed.contains(key.getName()));
    }
    private static byte[] boundedContent(COSStream stream,int cap) throws IOException {
        if(!keys(stream,STREAM_KEYS)||stream.getDictionaryObject(COSName.DECODE_PARMS)!=null)return null;
        COSBase filter=stream.getDictionaryObject(COSName.FILTER);
        if(filter instanceof COSArray array) {
            if(array.size()!=1)return null;
            filter=array.getObject(0);
        }
        if(filter!=null&&!COSName.FLATE_DECODE.equals(filter)&&!COSName.FL.equals(filter))return null;
        // COSStream.createInputStream may eagerly decode into a random-access buffer.
        // A single supported filter writes directly through this pre-allocation cap.
        var output=new java.io.ByteArrayOutputStream(Math.min(cap,8192));
        var bounded=new java.io.OutputStream() {
            @Override public void write(int value)throws IOException {
                if(output.size()>=cap)throw new IOException("PDF evidence limit");
                output.write(value);
            }
            @Override public void write(byte[] bytes,int offset,int length)throws IOException {
                if(length>cap-output.size())throw new IOException("PDF evidence limit");
                output.write(bytes,offset,length);
            }
        };
        try(var raw=stream.createRawInputStream()) {
            if(filter==null)raw.transferTo(bounded);
            else org.apache.pdfbox.filter.FilterFactory.INSTANCE.getFilter((COSName)filter).decode(raw,bounded,stream,0);
            return output.toByteArray();
        } catch(IOException invalidOrTooLarge){return null;}
    }
}
