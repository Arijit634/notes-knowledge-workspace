package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Deterministic provider-neutral token approximation: four ASCII characters or
 * one non-ASCII code point per unit. Prefix weights keep oversized blocks linear
 * in source length, and offsets always refer to the original UTF-16 source.
 */
final class MarkdownChunker {
    static final int TARGET=600,HARD=900,OVERLAP=80,MAX_SEGMENTS=512;
    List<DerivedSegment> chunk(String source) {
        if(source==null||source.length()>1_000_000) throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        int[] weights=weights(source);
        var result=new ArrayList<DerivedSegment>();var headings=new String[6];
        int start=0,offset=0,units=0;String ancestry="";boolean fenced=false;
        while(offset<source.length()) {
            int lineEnd=source.indexOf('\n',offset);if(lineEnd<0)lineEnd=source.length();else lineEnd++;
            String line=source.substring(offset,lineEnd);String trim=line.stripLeading();
            boolean fence=trim.startsWith("```")||trim.startsWith("~~~");
            int level=0;while(level<6&&level<trim.length()&&trim.charAt(level)=='#')level++;
            boolean heading=!fenced&&level>0&&trim.length()>level&&trim.charAt(level)==' ';
            if(heading && offset>start) { append(result,source,start,offset,ancestry); start=offset;units=0; }
            if(heading) {
                headings[level-1]=trim.substring(level+1).strip();for(int i=level;i<6;i++)headings[i]=null;
                ancestry=java.util.Arrays.stream(headings).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(" / "));
                if(ancestry.length()>1024)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            }
            int lineUnits=units(weights,offset,lineEnd);
            if(units+lineUnits>HARD && offset>start) { append(result,source,start,offset,ancestry);start=offset;units=0; }
            // Oversized blocks/lines use whitespace boundaries where possible. Never silently exceed the hard budget.
            while(units(weights,start,lineEnd)>HARD) {
                int end=boundary(source,weights,start,lineEnd,weights[start]+HARD*4);int cut=end;
                while(cut>start&&units(weights,start,cut)>HARD/2&&!Character.isWhitespace(source.charAt(cut-1)))cut--;
                if(units(weights,start,cut)<=HARD/2)cut=end;
                if(cut>start&&Character.isLowSurrogate(source.charAt(cut)))cut--;
                append(result,source,start,cut,ancestry);
                int overlap=boundary(source,weights,start,cut,Math.max(weights[start],weights[cut]-OVERLAP*4));
                while(overlap<cut&&!Character.isWhitespace(source.charAt(overlap)))overlap++;
                start=overlap==cut?cut:overlap;
            }
            units=units(weights,start,lineEnd);offset=lineEnd;
            if(fence)fenced=!fenced;
            if(!fenced&&units>=TARGET&&line.isBlank()) {append(result,source,start,offset,ancestry);start=offset;units=0;}
        }
        if(start<source.length())append(result,source,start,source.length(),ancestry);
        return List.copyOf(result);
    }
    static int estimatedUnits(String source) {return units(weights(source),0,source.length());}
    private static int[] weights(String source) {
        int[] weights=new int[source.length()+1];
        for(int i=0;i<source.length();) {
            int point=source.codePointAt(i),next=i+Character.charCount(point);
            if(next-i==2)weights[i+1]=weights[i];
            weights[next]=weights[i]+(point<128?1:4);i=next;
        }
        return weights;
    }
    private static int units(int[] weights,int start,int end){return (weights[end]-weights[start]+3)/4;}
    private static int boundary(String source,int[] weights,int start,int end,int ceiling) {
        int low=start,high=end;
        while(low<high){int middle=(low+high+1)>>>1;if(weights[middle]<=ceiling)low=middle;else high=middle-1;}
        if(low<source.length()&&low>start&&Character.isLowSurrogate(source.charAt(low)))low--;
        return low;
    }
    private static void append(List<DerivedSegment> result,String source,int start,int end,String heading) {
        if(source.substring(start,end).isBlank())return;
        if(result.size()>=MAX_SEGMENTS)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        result.add(DerivedSegment.note(source.substring(start,end),heading,start,end));
    }
}
