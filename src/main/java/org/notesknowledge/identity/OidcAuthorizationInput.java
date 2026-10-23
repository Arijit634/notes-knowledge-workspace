package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.MediaType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class OidcAuthorizationInput {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static String read(HttpServletRequest request,boolean login) {
        byte[] bytes;
        try{bytes=request.getInputStream().readNBytes(1025);}
        catch(java.io.IOException failure){throw failure(ApiFailureException.Kind.MALFORMED_REQUEST);}
        if(bytes.length>1024)throw failure(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        if(!request.getParameterMap().isEmpty())throw failure(ApiFailureException.Kind.MALFORMED_REQUEST);
        if(bytes.length==0)return login?"/notes":null;
        try {
            if(request.getContentType()==null||!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
                throw failure(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);
        }catch(org.springframework.http.InvalidMediaTypeException invalid){throw failure(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);}
        tools.jackson.databind.JsonNode root;
        try{root=JSON.readTree(java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString());}
        catch(tools.jackson.core.JacksonException|java.nio.charset.CharacterCodingException invalid){throw failure(ApiFailureException.Kind.MALFORMED_REQUEST);}
        if(root==null||!root.isObject())throw failure(ApiFailureException.Kind.MALFORMED_REQUEST);
        if(root.properties().stream().anyMatch(p->!p.getKey().equals("intendedDestination")))throw failure(ApiFailureException.Kind.MALFORMED_REQUEST);
        if(root.isEmpty())return login?"/notes":null;
        if(!login||root.size()!=1||!root.path("intendedDestination").isString()
            ||!OidcIntendedDestination.allowed(root.path("intendedDestination").stringValue()))
            throw failure(ApiFailureException.Kind.INVALID_CONTRACT_INPUT);
        return root.path("intendedDestination").stringValue();
    }
    private static ApiFailureException failure(ApiFailureException.Kind kind){return ApiFailureException.of(kind);}
    private OidcAuthorizationInput(){ }
}
