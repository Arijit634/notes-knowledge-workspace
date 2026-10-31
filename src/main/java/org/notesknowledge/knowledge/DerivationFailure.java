package org.notesknowledge.knowledge;

final class DerivationFailure extends RuntimeException {
    final KnowledgeWork.Failure category;
    final ProviderFailureDiagnostic diagnostic;
    DerivationFailure(KnowledgeWork.Failure category) { this(category,null); }
    DerivationFailure(KnowledgeWork.Failure category,ProviderFailureDiagnostic diagnostic) {
        super("Derivation unavailable",null,false,false);this.category=category;this.diagnostic=diagnostic;
    }
    boolean retryableProvider() {
        if(category!=KnowledgeWork.Failure.TRANSIENT_DEPENDENCY||diagnostic==null)return false;
        return switch(diagnostic.kind()) {
            case HTTP->diagnostic.httpStatus()!=null&&java.util.Set.of(500,502,503,504).contains(diagnostic.httpStatus());
            case SOCKET_TIMEOUT,INTERRUPTED_IO,TRANSPORT_IO->true;
            default->false;
        };
    }
}
