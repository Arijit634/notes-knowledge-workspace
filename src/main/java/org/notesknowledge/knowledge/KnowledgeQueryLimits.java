package org.notesknowledge.knowledge;

/** Extraction packs short sources; focused answers keep their smaller evidence window. */
final class KnowledgeQueryLimits {
    static final int EXTRACTION=32,FOCUSED=12,CHARACTERS=48_000;
    static int evidence(String task){return "extract".equals(task)?EXTRACTION:FOCUSED;}
    private KnowledgeQueryLimits(){}
}
