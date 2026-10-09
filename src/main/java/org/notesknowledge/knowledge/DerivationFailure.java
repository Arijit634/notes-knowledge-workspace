package org.notesknowledge.knowledge;

final class DerivationFailure extends RuntimeException {
    final KnowledgeWork.Failure category;
    final ProviderFailureDiagnostic diagnostic;
    DerivationFailure(KnowledgeWork.Failure category) { this(category,null); }
    DerivationFailure(KnowledgeWork.Failure category,ProviderFailureDiagnostic diagnostic) {
        super("Derivation unavailable",null,false,false);this.category=category;this.diagnostic=diagnostic;
    }
}
