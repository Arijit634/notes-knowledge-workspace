package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AiProcessingStatusService {
    record State(String status,String reason) { }
    record AttachmentState(UUID attachmentId,String status,String reason) { }
    record View(State note,List<AttachmentState> attachments) { }
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final PrivateDerivationSource sources;
    private final ProcessingPolicyService policies;
    private final AiDerivationProperties configuration;
    private final PrivateRepresentationRepository representations;
    AiProcessingStatusService(ObjectProvider<AccountEligibilityApi> accounts,PrivateDerivationSource sources,ProcessingPolicyService policies,
            AiDerivationProperties configuration,PrivateRepresentationRepository representations) {
        this.accounts=accounts;this.sources=sources;this.policies=policies;this.configuration=configuration;this.representations=representations;
    }
    @Transactional(timeout=3)
    View read(HttpServletRequest browser,UUID note) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof IdentitySessionPrincipal principal))
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        accounts.getObject().requireCurrentOwner(principal.userId(),browser);
        var rows=sources.noteSources(principal.userId(),note);
        var noteSource=rows.stream().filter(s->s.expected().attachmentId()==null).findFirst().orElseThrow();
        var noteState=state(noteSource);
        var attachments=rows.stream().filter(s->s.expected().attachmentId()!=null).map(s->{
            var state=state(s);return new AttachmentState(s.expected().attachmentId(),state.status(),state.reason());}).toList();
        return new View(noteState,attachments);
    }
    State state(PrivateDerivationSource.Metadata source) {
        if(!source.aiEnabled())return new State("excluded",source.expected().attachmentId()==null?"aiDisabled":"parentAiDisabled");
        if(!java.util.Set.of("active","archived").contains(source.lifecycle()))return new State("excluded","lifecycleExcluded");
        java.util.Optional<ProcessingPolicyService.AcknowledgedProcessingPolicy> policy;
        try {policy=policies.policyPrerequisite(source.expected().owner());}
        catch(ApiFailureException missing){return new State("blocked","policyUnavailable");}
        if(policy.isEmpty())return new State("blocked","policyAcknowledgementRequired");
        if(!configuration.configured()||!configuration.approvedPolicyFingerprint().equals(policy.get().fingerprint()))return new State("blocked","providerUnavailable");
        var lineage=EmbeddingLineage.create(configuration,policy.get(),source.modality());
        if(representations.ready(source.expected(),lineage))return new State("ready",null);
        var kind=source.expected().attachmentId()==null?KnowledgeWork.Kind.NOTE:KnowledgeWork.Kind.ATTACHMENT;
        String work=representations.workState(kind,source.expected(),lineage.id());
        boolean old=representations.oldRoot(source.expected());
        if("queued".equals(work)||"retry_wait".equals(work))return new State(old?"reprocessing":"queued",null);
        if("claimed".equals(work))return new State(old?"reprocessing":"processing",null);
        if("failed".equals(work))return new State("failed","processingFailed");
        if(old||"obsolete".equals(work))return new State("obsolete","sourceChanged");
        return new State("blocked","providerUnavailable");
    }
}
