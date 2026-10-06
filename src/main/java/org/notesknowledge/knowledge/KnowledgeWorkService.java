package org.notesknowledge.knowledge;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No scheduler or provider effects. Each invocation is one bounded local transaction.
 * queued/retry_wait -> claimed -> completed/failed/obsolete/retry_wait;
 * expired claimed -> fresh claimed (or failed when exhausted). Terminal states never reopen.
 */
@Service
public class KnowledgeWorkService {
    static final LeasePolicy POLICY=new LeasePolicy(Duration.ofSeconds(30),10);
    private final KnowledgeWorkRepository repository;
    private final PrivateAiSourceCurrentness sources;
    private final ProcessingPolicyService policies;
    KnowledgeWorkService(KnowledgeWorkRepository repository,PrivateAiSourceCurrentness sources,ProcessingPolicyService policies) {
        this.repository=repository;this.sources=sources;this.policies=policies;
    }
    @Transactional(timeout=3)
    public KnowledgeWork.Intent enqueueIfAbsent(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected expected) {
        if((kind==KnowledgeWork.Kind.ATTACHMENT)!=(expected.attachmentId()!=null)) throw new IllegalArgumentException("Source kind mismatch");
        return repository.enqueue(kind,expected);
    }
    @Transactional(timeout=3)
    public List<KnowledgeWork.Claim> claim(LeaseOwner owner,int limit) { return repository.claim(owner,POLICY,POLICY.checkedBatchSize(limit),false); }
    @Transactional(timeout=3)
    public List<KnowledgeWork.Claim> reclaim(LeaseOwner owner,int limit) { return repository.claim(owner,POLICY,POLICY.checkedBatchSize(limit),true); }
    @Transactional(timeout=3)
    public boolean heartbeat(KnowledgeWork.Claim claim) { return repository.heartbeat(claim,POLICY); }
    /** Metadata/policy prerequisites only. Future source access/provider dispatch must revalidate ALL additional gates. */
    @Transactional(timeout=3)
    public Optional<CurrentWorkPrerequisites> revalidate(KnowledgeWork.Claim claim) {
        if(!repository.currentLease(claim)) return Optional.empty();
        var policy=policies.policyPrerequisite(claim.intent().expected().owner());
        if(policy.isEmpty()||!sources.matches(claim.intent().expected())) return Optional.empty();
        return policy.map(p->
                new CurrentWorkPrerequisites(p.policyId(),p.version(),p.fingerprint()));
    }
    public record CurrentWorkPrerequisites(java.util.UUID policyId,long policyVersion,String policyFingerprint) {
        @Override public String toString() { return "CurrentWorkPrerequisites[REDACTED; NOT PROVIDER AUTHORITY]"; }
    }
    /** Future orchestration calls this only AFTER a real representation effect; nothing invokes it autonomously. */
    @Transactional(timeout=3)
    public boolean complete(KnowledgeWork.Claim c) { return revalidate(c).isPresent()&&repository.transition(c,"completed",null,false); }
    @Transactional(timeout=3)
    public boolean retry(KnowledgeWork.Claim c) { return repository.transition(c,"retry_wait",KnowledgeWork.Failure.TRANSIENT_DEPENDENCY,true); }
    @Transactional(timeout=3)
    public boolean fail(KnowledgeWork.Claim c,KnowledgeWork.Failure failure) {
        return repository.transition(c,"failed",java.util.Objects.requireNonNull(failure),false);
    }
    @Transactional(timeout=3)
    public boolean obsolete(KnowledgeWork.Claim c) { return repository.transition(c,"obsolete",KnowledgeWork.Failure.INVALID_SOURCE,false); }
}
