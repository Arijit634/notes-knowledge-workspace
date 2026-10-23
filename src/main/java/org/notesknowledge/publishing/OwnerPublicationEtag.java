package org.notesknowledge.publishing;

import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** The selected owner representation includes nullable private navigation, never public DTOs. */
@Component
class OwnerPublicationEtag {
    private final ObjectMapper json;
    OwnerPublicationEtag(ObjectMapper json){this.json=json;}
    String encode(PublicationRecord.OwnerView view,long snapshot,long generation) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(("nkw-owner-publication-v1\n"+snapshot+"\n"+generation+"\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(json.writeValueAsBytes(view));
            return '"'+Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())+'"';
        } catch(Exception unavailable){throw org.notesknowledge.websupport.ApiFailureException.of(
            org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    }
}
