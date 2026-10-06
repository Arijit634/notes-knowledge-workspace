package org.notesknowledge.knowledge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Inert stored text only. Explicit ASCII-host HTTP(S), max2048 UTF16 units; no URL/DNS/I/O API. */
final class UrlRecognizer {
    private static final Pattern START=Pattern.compile("https?://",Pattern.CASE_INSENSITIVE);
    record Match(String display,String comparisonKey,int offset,int length) {
        @Override public String toString() { return "UrlMatch[REDACTED]"; }
    }
    /** Returns true if a potential explicit URL exceeded the supported resource bound. */
    boolean scan(String text,Consumer<Match> consumer) {
        var starts=START.matcher(text);
        boolean truncated=false; int from=0;
        while(starts.find(from)) {
            int start=starts.start(),end=starts.end(),balance=0;
            boolean queryOrFragment=false;
            while(end<text.length()) {
                char c=text.charAt(end);
                if(Character.isWhitespace(c)||Character.isISOControl(c)||c=='<'||c=='>'||c=='"'||c=='\''||c=='`') break;
                if(c=='(') balance++;
                if(c==')' && balance--<=0) break;
                if(c=='?'||c=='#') queryOrFragment=true;
                end++;
            }
            int next=Math.max(end,starts.end());
            // Sentence punctuation is ambiguous only in prose paths. Never trim query/fragment data.
            if(!queryOrFragment)
                while(end>start && ".,;:!".indexOf(text.charAt(end-1))>=0) end--;
            if(end-start>2048) truncated=true;
            else {
                String value=text.substring(start,end);
                try {
                    var uri=new URI(value);
                    if(uri.getHost()!=null && !uri.getHost().isBlank()) {
                        // Only scheme/host case fold. Preserve port/path/query/fragment/escaping exactly.
                        int authorityStart=value.indexOf("://")+3;
                        int hostEnd=authorityStart+uri.getRawAuthority().length();
                        String authority=uri.getRawAuthority();
                        int hostStart=authority.lastIndexOf('@')+1;
                        int zone=authority.indexOf('%',hostStart);
                        // User-info and IPv6 interface-zone spelling may be case-sensitive; neither is a host label.
                        int foldEnd=zone<0?authority.length():zone;
                        String normalizedAuthority=authority.substring(0,hostStart)
                                +authority.substring(hostStart,foldEnd).toLowerCase(Locale.ROOT)+authority.substring(foldEnd);
                        String key=value.substring(0,authorityStart).toLowerCase(Locale.ROOT)
                                +normalizedAuthority+value.substring(hostEnd);
                        consumer.accept(new Match(value,key,start,end-start));
                    }
                } catch(URISyntaxException malformed) { /* Outside the explicitly supported URI syntax; inert text. */ }
            }
            from=next;
            if(from>=text.length()) break;
        }
        return truncated;
    }
}
