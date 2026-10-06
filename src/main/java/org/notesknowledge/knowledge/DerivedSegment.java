package org.notesknowledge.knowledge;

/** Typed provenance; transcript identity is its stable representation ordinal. Coordinates are normalized. */
record DerivedSegment(String text,String kind,String heading,Integer start,Integer end,Integer page,
        Double timeStart,Double timeEnd,Double x,Double y,Double width,Double height) {
    DerivedSegment {
        if(text==null||text.isBlank()||text.length()>12000||heading==null||heading.length()>1024
                ||!java.util.Set.of("note_text","pdf_text","whole_image","image_region","transcript","video_scene").contains(kind)
                ||(start==null)!=(end==null)||start!=null&&(start<0||end<start||end>1000000)
                ||page!=null&&(page<1||page>500)||(timeStart==null)!=(timeEnd==null)
                ||timeStart!=null&&(!Double.isFinite(timeStart)||!Double.isFinite(timeEnd)||timeStart<0||timeEnd<timeStart))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        if(kind.equals("note_text")&&(start==null||page!=null||timeStart!=null||x!=null)
                ||kind.equals("pdf_text")&&(page==null||timeStart!=null||x!=null)
                ||kind.equals("whole_image")&&(start!=null||page!=null||timeStart!=null||x!=null)
                ||kind.equals("image_region")&&(start!=null||page!=null||timeStart!=null)
                ||java.util.Set.of("transcript","video_scene").contains(kind)&&(start!=null||timeStart==null||page!=null||x!=null))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        boolean region=x!=null||y!=null||width!=null||height!=null;
        if(region && (x==null||y==null||width==null||height==null||!Double.isFinite(x)||!Double.isFinite(y)
                ||!Double.isFinite(width)||!Double.isFinite(height)||x<0||y<0||width<=0||height<=0||x+width>1||y+height>1)
                ||kind.equals("image_region")&&!region || region&&!kind.equals("image_region"))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
    }
    static DerivedSegment note(String text,String heading,int start,int end) {
        return new DerivedSegment(text,"note_text",heading,start,end,null,null,null,null,null,null,null);
    }
    @Override public String toString() { return "DerivedSegment[private text omitted]"; }
}
