package org.notesknowledge.publishing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stateless, expiring preview integrity, deliberately not authentication or authorization. */
@Component
class PublicationPreviewFingerprintCodec {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final byte[] key;
    private final String version;
    private final Clock clock;
    PublicationPreviewFingerprintCodec(@Value("${publishing.preview.key-base64:}") String encoded,
        @Value("${publishing.preview.key-version:k1}") String version,Clock clock) {
        try {key=encoded.isBlank()?null:Base64.getDecoder().decode(encoded);}
        catch(IllegalArgumentException failure){throw new IllegalArgumentException("Invalid preview key configuration");}
        if(key!=null&&key.length!=32||!version.matches("[A-Za-z0-9_-]{1,16}"))throw new IllegalArgumentException("Invalid preview key configuration");
        this.version=version;this.clock=clock;
    }
    String issue(PublicationTransactions.Prepared p) {
        byte[] nonce=new byte[16];RANDOM.nextBytes(nonce);
        String prefix="p1."+version+"."+(clock.instant().getEpochSecond()+300)+"."+encode(nonce);
        return prefix+"."+encode(mac(prefix,p));
    }
    void require(String token,PublicationTransactions.Prepared p) {
        if(token==null||token.length()>256)throw stale();
        String[] parts=token.split("\\.",-1);
        if(parts.length!=5||!parts[0].equals("p1")||!parts[1].equals(version))throw stale();
        try {
            long expires=Long.parseLong(parts[2]);long now=clock.instant().getEpochSecond();
            if(expires<=now||expires>now+300||decode(parts[3]).length!=16||decode(parts[4]).length!=32)throw stale();
            String prefix=String.join(".",parts[0],parts[1],parts[2],parts[3]);
            if(!MessageDigest.isEqual(mac(prefix,p),decode(parts[4])))throw stale();
        }catch(IllegalArgumentException failure){throw stale();}
    }
    private byte[] mac(String prefix,PublicationTransactions.Prepared p) {
        if(key==null)throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeUTF("PUBLIC_COPY");out.writeUTF(prefix);out.writeUTF(p.owner().toString());
            out.writeUTF(p.source().noteId().toString());out.writeLong(p.source().revision());
            // Large content is digested inside the authenticated material, never exposed as a client-trusted hash.
            out.write(MessageDigest.getInstance("SHA-256").digest(p.source().title().getBytes(StandardCharsets.UTF_8)));
            out.write(MessageDigest.getInstance("SHA-256").digest(p.source().markdown().getBytes(StandardCharsets.UTF_8)));
            out.writeInt(p.source().tags().size());for(var tag:p.source().tags())out.writeUTF(tag);
            out.writeUTF(p.author().projectionId().toString());out.writeLong(p.author().generation());
            out.writeInt(p.selection().size());
            for(var media:p.selection()) {out.writeUTF(media.id().toString());out.writeLong(media.revision());
                out.writeUTF(media.kind());out.writeUTF(media.type());out.writeUTF(media.name());out.writeLong(media.size());
                out.writeUTF(String.valueOf(media.width()));out.writeUTF(String.valueOf(media.height()));
                out.writeUTF(String.valueOf(media.duration()));out.writeUTF(String.valueOf(media.pages()));}
            out.flush();var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(bytes.toByteArray());
        }catch(GeneralSecurityException|java.io.IOException failure){throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    }
    private static String encode(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static byte[] decode(String value){
        if(!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
        byte[] decoded=Base64.getUrlDecoder().decode(value);
        if(!encode(decoded).equals(value))throw new IllegalArgumentException();return decoded;
    }
    private static ApiFailureException stale(){return ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);}
}
