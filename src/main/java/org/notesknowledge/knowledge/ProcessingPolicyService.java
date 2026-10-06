package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProcessingPolicyService {
    record View(UUID processingPolicyId,String policyCode,long policyVersion,String policyFingerprint,
            String disclosureRevision,Instant effectiveAt,boolean acknowledged,boolean acknowledgementRequired) {
        @Override public String toString() { return "ProcessingPolicyView[REDACTED]"; }
    }
    /** One prerequisite only. NOT a SourceAiPermit or provider/content authorization. */
    record AcknowledgedProcessingPolicy(UUID policyId,long version,String fingerprint) {
        @Override public String toString() { return "AcknowledgedProcessingPolicy[REDACTED]"; }
    }
    private final ProcessingPolicyRepository repository;
    private final KnowledgePolicyProperties configuration;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    ProcessingPolicyService(ProcessingPolicyRepository repository,KnowledgePolicyProperties configuration,ObjectProvider<AccountEligibilityApi> accounts) {
        this.repository=repository;this.configuration=configuration;this.accounts=accounts;
    }
    private UUID owner(HttpServletRequest browser) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof IdentitySessionPrincipal principal)
                ||auth.getAuthorities().stream().noneMatch(a->"ROLE_USER".equals(a.getAuthority())))
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        if(accounts.getIfAvailable()==null) throw unavailable();
        accounts.getObject().requireCurrentOwner(principal.userId(),browser);
        return principal.userId();
    }
    private ProcessingPolicyRepository.Policy current() {
        String code=configuration.code();
        if(code==null||!code.matches("[a-z][a-z0-9_.-]{0,63}")) throw unavailable();
        return repository.current(code).orElseThrow(ProcessingPolicyService::unavailable);
    }
    @Transactional
    View read(HttpServletRequest browser) {
        UUID user=owner(browser);var policy=current();boolean ack=repository.acknowledged(user,policy);
        return new View(policy.id(),policy.code(),policy.version(),policy.fingerprint(),policy.disclosureRevision(),policy.effectiveAt(),ack,!ack);
    }
    @Transactional
    void acknowledge(HttpServletRequest browser,PolicyAcknowledgementRequest request) {
        UUID user=owner(browser);var policy=current();
        if(!policy.id().equals(request.processingPolicyId())||!policy.code().equals(request.policyCode())
                ||policy.version()!=request.policyVersion()||!policy.fingerprint().equals(request.policyFingerprint())
                ||!policy.disclosureRevision().equals(request.disclosureRevision()))
            throw ApiFailureException.of(ApiFailureException.Kind.PROCESSING_POLICY_CHANGED);
        repository.acknowledge(user,policy);
    }
    @Transactional
    Optional<AcknowledgedProcessingPolicy> policyPrerequisite(UUID expectedUser) {
        if(accounts.getIfAvailable()==null||!accounts.getObject().isEligible(expectedUser)) return Optional.empty();
        var policy=current();
        return repository.acknowledged(expectedUser,policy)
                ?Optional.of(new AcknowledgedProcessingPolicy(policy.id(),policy.version(),policy.fingerprint())):Optional.empty();
    }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
