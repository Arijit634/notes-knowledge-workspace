package org.notesknowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import software.amazon.awssdk.http.SdkHttpRequest;

/** Independent JDK HMAC check at a synthetic HTTP boundary, not a production signer.
 * Only the two reserved fake fixture identities are understood. Never emits a
 * header, credential, signature, canonical request or derivative as diagnostics.
 * Canonicalization follows AWS's single-chunk SigV4 specification. */
final class SyntheticSigV4Verifier {
    static void require(SdkHttpRequest request) {
        try {
            String authorization=request.firstMatchingHeader("Authorization").orElseThrow();
            var match=java.util.regex.Pattern.compile("AWS4-HMAC-SHA256 Credential=(synthetic-access-key|synthetic-rotated)/([0-9]{8})/(ap-mumbai-1)/s3/aws4_request,\\s*SignedHeaders=([a-z0-9;-]+),\\s*Signature=([0-9a-f]{64})").matcher(authorization);
            if(!match.matches())throw new IllegalArgumentException();
            String secret=match.group(1).equals("synthetic-rotated")?"synthetic-rotated-secret":"synthetic-secret-key";
            var names=List.of(match.group(4).split(";"));
            if(!names.equals(names.stream().distinct().sorted().toList())||!names.contains("host")||!names.contains("x-amz-date"))throw new IllegalArgumentException();
            var headers=new StringBuilder();
            for(String name:names){
                String value=name.equals("host")?request.firstMatchingHeader("Host").orElseGet(()->request.getUri().getHost()
                    +(request.getUri().getPort()!=-1&&request.getUri().getPort()!=443?":"+request.getUri().getPort():"")):String.join(",",request.matchingHeaders(name));
                if(value.isEmpty())throw new IllegalArgumentException();headers.append(name).append(':').append(value.strip().replaceAll("\\s+"," ")).append('\n');
            }
            var query=new ArrayList<String>();request.rawQueryParameters().forEach((name,values)->values.forEach(value->query.add(encode(name)+"="+encode(value))));Collections.sort(query);
            String payload=request.firstMatchingHeader("x-amz-content-sha256").orElseThrow();
            if(!payload.equals("UNSIGNED-PAYLOAD")&&!payload.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            String canonical=String.join("\n",request.method().name(),request.encodedPath(),String.join("&",query),headers.toString(),match.group(4),payload);
            String date=request.firstMatchingHeader("x-amz-date").orElseThrow();if(!date.startsWith(match.group(2)))throw new IllegalArgumentException();
            String scope=match.group(2)+"/"+match.group(3)+"/s3/aws4_request";
            String toSign="AWS4-HMAC-SHA256\n"+date+"\n"+scope+"\n"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] key=("AWS4"+secret).getBytes(StandardCharsets.UTF_8);
            for(String component:List.of(match.group(2),match.group(3),"s3","aws4_request"))key=hmac(key,component);
            if(!java.security.MessageDigest.isEqual(hmac(key,toSign),HexFormat.of().parseHex(match.group(5))))throw new IllegalArgumentException();
        }catch(Exception failure){throw new AssertionError("Synthetic S3 signature mismatch");}
    }
    private static String encode(String value){return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~");}
    private static byte[] hmac(byte[] key,String value)throws Exception {var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
}
