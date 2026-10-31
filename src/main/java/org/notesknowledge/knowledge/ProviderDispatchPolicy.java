package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.stereotype.Component;

/** Additional dispatch prerequisite; an approval never establishes source authority/currentness. */
@Component
class ProviderDispatchPolicy {
    static final int MAX_APPROVED_SOURCES=2048;
    static final int MAX_APPROVED_QUERIES=256;
    static final int MAX_APPROVED_CHILDREN=256;
    private final ProviderDispatchProperties properties;
    private final boolean protectedLocalhost;
    ProviderDispatchPolicy(ProviderDispatchProperties properties) {
        this(properties,false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    ProviderDispatchPolicy(ProviderDispatchProperties properties,org.springframework.beans.factory.ObjectProvider<org.notesknowledge.LocalhostMode> localhost) {
        this(properties,properties.syntheticWorkspaceOwner()!=null&&localhost.getIfAvailable()!=null);
    }
    ProviderDispatchPolicy(ProviderDispatchProperties properties,boolean protectedLocalhost) {
        this.properties=properties;this.protectedLocalhost=protectedLocalhost;
        // Validate outside configuration binding so rejected fingerprints are not echoed as binding values.
        validate(properties);
        if(properties.syntheticWorkspaceOwner()!=null&&!protectedLocalhost)throw new IllegalArgumentException("Protected synthetic workspace required");
    }
    static void validate(ProviderDispatchProperties properties) {
        var fingerprints=properties.approvedSourceFingerprints();
        var queries=properties.approvedQueryFingerprints();
        var children=properties.approvedChildFingerprints();
        if(properties.dispatchPolicy()!=null&&!properties.dispatchPolicy().isBlank()
                &&!"unpaid-synthetic-demo".equals(properties.dispatchPolicy())
                ||fingerprints!=null&&(fingerprints.size()>MAX_APPROVED_SOURCES
                ||fingerprints.stream().anyMatch(f->f==null||!f.matches("[0-9a-f]{64}")))
                ||queries!=null&&(queries.size()>MAX_APPROVED_QUERIES||queries.stream().anyMatch(f->f==null||!f.matches("[0-9a-f]{64}")))
                ||children.size()>MAX_APPROVED_CHILDREN||children.stream().anyMatch(f->f==null||!f.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid provider dispatch policy configuration");
    }
    boolean permitsQuery(AiDerivationProperties configuration,String normalizedQuery) {
        return permitsQuery(configuration,null,normalizedQuery);
    }
    boolean permitsQuery(AiDerivationProperties configuration,java.util.UUID owner,String normalizedQuery) {
        validate(properties);
        return "synthetic".equals(configuration.provider())&&"synthetic".equals(configuration.tier())
            ||permitsUnpaidGeminiQuery(configuration,owner,normalizedQuery);
    }
    boolean permitsUnpaidGeminiQuery(AiDerivationProperties configuration,String normalizedQuery) {
        return permitsUnpaidGeminiQuery(configuration,null,normalizedQuery);
    }
    boolean permitsUnpaidGeminiQuery(AiDerivationProperties configuration,java.util.UUID owner,String normalizedQuery) {
        validate(properties);
        return "gemini".equals(configuration.provider())&&"unpaid".equals(configuration.tier())
            &&"global".equals(configuration.region())&&"unpaid-synthetic-demo".equals(properties.dispatchPolicy())
            &&normalizedQuery!=null&&properties.approvedQueryFingerprints()!=null
            &&(workspace(owner)||properties.approvedQueryFingerprints().contains(queryFingerprint(normalizedQuery)));
    }
    private boolean workspace(java.util.UUID owner) {
        return protectedLocalhost&&owner!=null&&owner.equals(properties.syntheticWorkspaceOwner());
    }
    /** Exactly the request's strip/NFKC normalization; no case-folding or inferred approval. */
    static String queryFingerprint(String query) {
        String canonical="nkw-private-query-v1\n"+KnowledgeQueryRequest.normalize(query);
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    boolean permits(AiDerivationProperties configuration,PrivateAiSourceCurrentness.Expected source,String modality) {
        if(!Set.of("note","image","audio","video","pdf").contains(modality))return false;
        // Explicit deterministic CI providers have no network adapter. This exception cannot authorize Google.
        if("synthetic".equals(configuration.provider())&&"synthetic".equals(configuration.tier()))return true;
        return permitsUnpaidGemini(configuration,source);
    }
    boolean permitsUnpaidGemini(AiDerivationProperties configuration,PrivateAiSourceCurrentness.Expected source) {
        validate(properties);
        return "gemini".equals(configuration.provider())&&"unpaid".equals(configuration.tier())
            &&"global".equals(configuration.region())&&"unpaid-synthetic-demo".equals(properties.dispatchPolicy())
            &&properties.approvedSourceFingerprints()!=null&&(workspace(source.owner())||properties.approvedSourceFingerprints().contains(fingerprint(source)));
    }
    boolean permitsUnpaidGeminiChild(AiDerivationProperties configuration,TransformedMediaIdentity child) {
        validate(properties);
        return child!=null&&permitsUnpaidGemini(configuration,child.original())
            &&configuration.provider().equals(child.provider())
            &&child.model().equals("video_audio".equals(child.scope().kind())?configuration.soundtrackModel():configuration.mediaModel())
            &&configuration.approvedPolicyFingerprint().equals(child.policySha256())
            &&(workspace(child.original().owner())||properties.approvedChildFingerprints().contains(child.approvalFingerprint()));
    }
    /** SHA-256 over UTF-8, LF-separated fixed fields, no trailing LF; '-' represents absent Attachment fields. */
    static String fingerprint(PrivateAiSourceCurrentness.Expected source) {
        String canonical=String.join("\n","nkw-private-source-v1",source.owner().toString(),source.noteId().toString(),
            source.attachmentId()==null?"-":source.attachmentId().toString(),Long.toString(source.revision()),
            Long.toString(source.aiGeneration()),source.attachmentGeneration()==null?"-":source.attachmentGeneration().toString());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
