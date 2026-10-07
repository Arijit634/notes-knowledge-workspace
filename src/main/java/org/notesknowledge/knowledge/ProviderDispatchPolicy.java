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
    static final int MAX_APPROVED_SOURCES=256;
    private final ProviderDispatchProperties properties;
    ProviderDispatchPolicy(ProviderDispatchProperties properties) {
        this.properties=properties;
        // Validate outside configuration binding so rejected fingerprints are not echoed as binding values.
        validate(properties);
    }
    static void validate(ProviderDispatchProperties properties) {
        var fingerprints=properties.approvedSourceFingerprints();
        var queries=properties.approvedQueryFingerprints();
        if(properties.dispatchPolicy()!=null&&!properties.dispatchPolicy().isBlank()
                &&!"unpaid-synthetic-demo".equals(properties.dispatchPolicy())
                ||fingerprints!=null&&(fingerprints.size()>MAX_APPROVED_SOURCES
                ||fingerprints.stream().anyMatch(f->f==null||!f.matches("[0-9a-f]{64}")))
                ||queries!=null&&(queries.size()>MAX_APPROVED_SOURCES||queries.stream().anyMatch(f->f==null||!f.matches("[0-9a-f]{64}"))))
            throw new IllegalArgumentException("Invalid provider dispatch policy configuration");
    }
    boolean permitsQuery(AiDerivationProperties configuration,String normalizedQuery) {
        validate(properties);
        return "synthetic".equals(configuration.provider())&&"synthetic".equals(configuration.tier())
            ||permitsUnpaidGeminiQuery(configuration,normalizedQuery);
    }
    boolean permitsUnpaidGeminiQuery(AiDerivationProperties configuration,String normalizedQuery) {
        validate(properties);
        return "gemini".equals(configuration.provider())&&"unpaid".equals(configuration.tier())
            &&"global".equals(configuration.region())&&"unpaid-synthetic-demo".equals(properties.dispatchPolicy())
            &&normalizedQuery!=null&&properties.approvedQueryFingerprints()!=null
            &&properties.approvedQueryFingerprints().contains(queryFingerprint(normalizedQuery));
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
            &&properties.approvedSourceFingerprints()!=null&&properties.approvedSourceFingerprints().contains(fingerprint(source));
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
