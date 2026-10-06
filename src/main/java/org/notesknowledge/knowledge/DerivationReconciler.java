package org.notesknowledge.knowledge;

import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DerivationReconciler {
    private final PrivateDerivationSource sources;
    private final ProcessingPolicyService policies;
    private final AiDerivationProperties configuration;
    private final PrivateRepresentationRepository representations;
    private final KnowledgeWorkRepository work;
    private final org.springframework.beans.factory.ObjectProvider<TextEmbeddingPort> embeddings;
    private final org.springframework.beans.factory.ObjectProvider<MediaUnderstandingPort> media;
    DerivationReconciler(PrivateDerivationSource sources,ProcessingPolicyService policies,AiDerivationProperties configuration,
            PrivateRepresentationRepository representations,KnowledgeWorkRepository work,
            org.springframework.beans.factory.ObjectProvider<TextEmbeddingPort> embeddings,
            org.springframework.beans.factory.ObjectProvider<MediaUnderstandingPort> media) {
        this.sources=sources;this.policies=policies;this.configuration=configuration;this.representations=representations;this.work=work;
        this.embeddings=embeddings;this.media=media;
    }
    /** At most 25 metadata records/enqueues per short transaction; scheduler carries a bounded cursor. */
    @Transactional(timeout=3)
    PrivateDerivationSource.Cursor page(PrivateDerivationSource.Cursor after) {
        var embedding=embeddings.getIfAvailable();
        if(!configuration.configured()||embedding==null||!embedding.available())return null;
        var inventory=sources.inventory(after,25);
        for(var source:inventory.sources()) {
            if(!source.aiEnabled()||!java.util.Set.of("active","archived").contains(source.lifecycle()))continue;
            if(!java.util.Set.of("note","pdf").contains(source.modality())&&(media.getIfAvailable()==null||!media.getIfAvailable().available()))continue;
            var policy=policies.policyPrerequisite(source.expected().owner());
            if(policy.isEmpty()||!configuration.approvedPolicyFingerprint().equals(policy.get().fingerprint()))continue;
            var lineage=EmbeddingLineage.create(configuration,policy.get(),source.modality());
            var kind=source.expected().attachmentId()==null?KnowledgeWork.Kind.NOTE:KnowledgeWork.Kind.ATTACHMENT;
            // Failed exact expectations remain terminal. An invalidated completed
            // Attachment can need reconstruction after parent lifecycle restoration.
            String state=representations.workState(kind,source.expected(),lineage.id());
            if(!representations.ready(source.expected(),lineage)&&(state==null||state.equals("obsolete")||state.equals("completed")))
                work.enqueue(kind,source.expected(),lineage.id());
        }
        return inventory.next();
    }
}
