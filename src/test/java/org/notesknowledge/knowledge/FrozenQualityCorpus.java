package org.notesknowledge.knowledge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public-safe fictional judgments frozen independently of provider output. Not a semantic oracle. */
final class FrozenQualityCorpus {
    static final long SEED=20261010L;
    record Note(String id,String title,String body,boolean aiEnabled) { }
    record Query(String id,String split,String category,String text,Map<String,Integer> gold,String expected) {
        Query {gold=Map.copyOf(gold);}
    }
    static List<Note> notes() {
        return rows("notes").stream().map(r->new Note(r[0],r[1],r[2].equals("LONG_INVENTORY")?
            "# Household inventory\n"+"The fictional storage shelf contains spare cardboard boxes and folded cloth.\n".repeat(100)
            +"\n# Final reserve\nThe reserve color is amber. This fact appears only at the end of the inventory.":r[2],Boolean.parseBoolean(r[3]))).toList();
    }
    static List<Query> queries() {
        return rows("queries").stream().map(r->{var gold=new LinkedHashMap<String,Integer>();
            if(!r[4].equals("-"))for(String entry:r[4].split(",")){var parts=entry.split(":");gold.put(parts[0],parts.length==2?Integer.parseInt(parts[1]):2);}
            return new Query(r[0],r[1],r[2],r[3],gold,r[5]);}).toList();
    }
    private static List<String[]> rows(String kind) {
        try(var input=FrozenQualityCorpus.class.getResourceAsStream("/knowledge/quality-v1-"+kind+".tsv")) {
            if(input==null)throw new IllegalStateException("Missing frozen corpus");
            return new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8)).lines()
                .filter(s->!s.isBlank()&&!s.startsWith("#")).map(s->s.split("\t",-1)).toList();
        }catch(java.io.IOException failure){throw new IllegalStateException("Frozen corpus unavailable",failure);}
    }
    private FrozenQualityCorpus(){ }
}
