package org.notesknowledge.knowledge.spi;

import java.util.Optional;
import java.util.UUID;

/** Only committed copied public text crosses this seam; no private source or object reference. */
public interface PublicKnowledgeSource {
    record Expected(UUID publication,long generation,long snapshot) { }
    record Source(Expected expected,String markdown) {
        @Override public String toString(){return "PublicKnowledgeSource[REDACTED]";}
    }
    Optional<Source> resolve(Expected expected,boolean lock);
    boolean current(Expected expected,boolean lock);
    record Inventory(java.util.List<Expected> sources,UUID next) { }
    Inventory inventory(UUID after,int limit);
}
