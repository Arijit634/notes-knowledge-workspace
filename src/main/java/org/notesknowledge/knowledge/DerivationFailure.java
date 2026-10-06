package org.notesknowledge.knowledge;

final class DerivationFailure extends RuntimeException {
    final KnowledgeWork.Failure category;
    DerivationFailure(KnowledgeWork.Failure category) { super("Derivation unavailable",null,false,false);this.category=category; }
}
