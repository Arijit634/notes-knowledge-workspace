package org.notesknowledge.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate key purpose from retained operation material. Owner/expiry are authenticated locators only. */
@Component
final class KnowledgeOperationHandleCodec {
    record Location(UUID work,UUID owner,String purpose,Instant expires) {
        @Override public String toString(){return "OperationLocation[REDACTED]";}
    }
    private final String version,previousVersion;
    private final byte[] key,previous;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    KnowledgeOperationHandleCodec(@Value("${knowledge.operations.handle.key-version:v1}") String version,
        @Value("${knowledge.operations.handle.key-base64:}") String key,
        @Value("${knowledge.operations.handle.previous-key-version:}") String previousVersion,
        @Value("${knowledge.operations.handle.previous-key-base64:}") String previous,Clock clock) {
        if(!version.matches("[A-Za-z0-9_-]{1,16}")||(!previousVersion.isEmpty()&&!previousVersion.matches("[A-Za-z0-9_-]{1,16}"))||version.equals(previousVersion))throw new IllegalArgumentException("Invalid handle key version");
        this.version=version;this.previousVersion=previousVersion;this.key=parse(key);this.previous=parse(previous);this.clock=clock;
    }
    String encode(Location l) {
        if(key==null)throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        byte purpose=switch(l.purpose()){case "semantic_corpus"->1;case "deterministic_corpus"->2;default->throw new IllegalArgumentException("Invalid operation purpose");};
        var plain=ByteBuffer.allocate(42).put((byte)1).putLong(l.work().getMostSignificantBits()).putLong(l.work().getLeastSignificantBits())
            .putLong(l.owner().getMostSignificantBits()).putLong(l.owner().getLeastSignificantBits()).put(purpose).putLong(l.expires().getEpochSecond());
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(version));
            byte[] sealed=cipher.doFinal(plain.array());return version+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(70).put(nonce).put(sealed).array());
        }catch(java.security.GeneralSecurityException failure){throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    }
    Location decode(String handle,UUID owner) {
        if(handle==null||!handle.matches("[A-Za-z0-9_-]{1,16}\\.[A-Za-z0-9_-]{94}"))throw missing();
        int dot=handle.indexOf('.');String v=handle.substring(0,dot);byte[] selected=v.equals(version)?key:v.equals(previousVersion)?previous:null;
        if(selected==null)throw missing();
        try {
            byte[] frame=Base64.getUrlDecoder().decode(handle.substring(dot+1));
            if(frame.length!=70||!Base64.getUrlEncoder().withoutPadding().encodeToString(frame).equals(handle.substring(dot+1)))throw missing();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(selected,"AES"),new GCMParameterSpec(128,frame,0,12));cipher.updateAAD(aad(v));
            var p=ByteBuffer.wrap(cipher.doFinal(frame,12,58));if(p.get()!=1)throw missing();
            UUID work=new UUID(p.getLong(),p.getLong()),actualOwner=new UUID(p.getLong(),p.getLong());
            String purpose=switch(p.get()){case 1->"semantic_corpus";case 2->"deterministic_corpus";default->throw missing();};
            Instant expiry=Instant.ofEpochSecond(p.getLong());if(!owner.equals(actualOwner)||!clock.instant().isBefore(expiry))throw missing();
            return new Location(work,owner,purpose,expiry);
        }catch(java.security.GeneralSecurityException|IllegalArgumentException failure){throw missing();}
    }
    private static byte[] aad(String version){return ("knowledge-operation-handle:"+version).getBytes(StandardCharsets.US_ASCII);}
    private static byte[] parse(String encoded){if(encoded==null||encoded.isBlank())return null;try{byte[] k=Base64.getDecoder().decode(encoded);if(k.length!=32)throw new IllegalArgumentException();return k;}catch(IllegalArgumentException failure){throw new IllegalArgumentException("Invalid operation handle key");}}
    private static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
