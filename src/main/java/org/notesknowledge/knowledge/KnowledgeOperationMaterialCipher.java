package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated locally resolved key ring. Envelopes are never authority or diagnostic data. */
@Component
final class KnowledgeOperationMaterialCipher {
    enum Kind { INPUT, RESULT }
    static final int INPUT_LIMIT=16*1024, RESULT_LIMIT=1024*1024;
    record Context(UUID work,UUID owner,String purpose,Kind kind,long version) {
        Context {
            if(work==null||owner==null||kind==null||version<0
                ||!java.util.Set.of("semantic_corpus","deterministic_corpus").contains(purpose)) throw new IllegalArgumentException("Invalid envelope context");
        }
        @Override public String toString(){return "OperationEnvelopeContext[REDACTED]";}
    }
    record Envelope(byte[] ciphertext,byte[] nonce,String keyVersion) {
        Envelope {ciphertext=ciphertext.clone();nonce=nonce.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public String toString(){return "OperationEnvelope[REDACTED]";}
    }
    private final SecureRandom random=new SecureRandom();
    private final String currentVersion,previousVersion;
    private final SecretKey current,previous;
    KnowledgeOperationMaterialCipher(@Value("${knowledge.operations.envelope.key-version:v1}") String currentVersion,
            @Value("${knowledge.operations.envelope.key-base64:}") String current,
            @Value("${knowledge.operations.envelope.previous-key-version:}") String previousVersion,
            @Value("${knowledge.operations.envelope.previous-key-base64:}") String previous) {
        if(!currentVersion.matches("[A-Za-z0-9_.-]{1,32}")||(!previousVersion.isEmpty()&&!previousVersion.matches("[A-Za-z0-9_.-]{1,32}"))
            ||currentVersion.equals(previousVersion))throw new IllegalArgumentException("Invalid operation key version");
        this.currentVersion=currentVersion;this.previousVersion=previousVersion;this.current=key(current);this.previous=key(previous);
    }
    Envelope seal(Context context,byte[] material) {
        int limit=context.kind()==Kind.INPUT?INPUT_LIMIT:RESULT_LIMIT;
        if(material==null||material.length==0||material.length>limit)throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        if(current==null)throw unavailable();
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,current,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(context,currentVersion));return new Envelope(cipher.doFinal(material),nonce,currentVersion);
        } catch(GeneralSecurityException failure){throw unavailable();}
    }
    byte[] open(Context context,Envelope envelope) {
        if(envelope==null||envelope.nonce().length!=12||envelope.ciphertext().length<17
            ||envelope.ciphertext().length>(context.kind()==Kind.INPUT?INPUT_LIMIT:RESULT_LIMIT)+16)throw unavailable();
        SecretKey selected=envelope.keyVersion().equals(currentVersion)?current:envelope.keyVersion().equals(previousVersion)?previous:null;
        if(selected==null)throw unavailable();
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,selected,new GCMParameterSpec(128,envelope.nonce()));
            cipher.updateAAD(aad(context,envelope.keyVersion()));return cipher.doFinal(envelope.ciphertext());
        } catch(GeneralSecurityException failure){throw unavailable();}
    }
    private static byte[] aad(Context c,String key) {
        return String.join("\n","knowledge-operation-envelope",c.work().toString(),c.owner().toString(),"private_knowledge_operation",
            c.purpose(),c.kind().name(),"schema-1",Long.toString(c.version()),key).getBytes(StandardCharsets.US_ASCII);
    }
    private static SecretKey key(String encoded) {
        if(encoded==null||encoded.isBlank())return null;
        try {byte[] bytes=Base64.getDecoder().decode(encoded);if(bytes.length!=32)throw new IllegalArgumentException();return new SecretKeySpec(bytes,"AES");}
        catch(IllegalArgumentException malformed){throw new IllegalArgumentException("Operation key must be base64 encoded 256 bits");}
    }
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
}
