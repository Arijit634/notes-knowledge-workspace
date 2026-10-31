package org.notesknowledge.knowledge;

/** Bounded metadata only: never retain a provider exception, message, body or request. */
record ProviderFailureDiagnostic(Stage stage, Kind kind, Integer httpStatus,
        Elapsed elapsed, Reach providerReached, boolean structurallyValidResponse, Detail detail) {
    enum Detail { UNSPECIFIED, COMPLETION, COMPLETION_MAX_TOKENS, COMPLETION_POLICY, COMPLETION_OTHER,
        RESPONSE_SHAPE, RESPONSE_CANDIDATES, RESPONSE_PARTS, RESPONSE_THOUGHT_PART, RESPONSE_NON_TEXT_PART,
        SDK_RESPONSE_JSON, JSON_SCHEMA, FLAG_SCOPE, SEGMENT_HEADING, SEGMENT_KIND,
        REQUEST_SCHEMA, REQUEST_VIDEO_CONFIGURATION, REQUEST_THINKING_CONFIGURATION, REQUEST_MODEL, REQUEST_MEDIA_TYPE,
        TEMPORAL_EVIDENCE, TEMPORAL_TYPE, TEMPORAL_ORDER, TEMPORAL_BOUNDS, ORIGINAL_SCOPE }
    ProviderFailureDiagnostic(Stage stage,Kind kind,Integer httpStatus,Elapsed elapsed,Reach reach,boolean valid){
        this(stage,kind,httpStatus,elapsed,reach,valid,Detail.UNSPECIFIED);
    }
    enum Stage { EMBEDDING, GENERATION, MEDIA }
    enum Kind { HTTP, SOCKET_TIMEOUT, INTERRUPTED_IO, TRANSPORT_IO, CLIENT_CONFIGURATION, PARSING, UNKNOWN }
    enum Elapsed { UNDER_ONE_SECOND, ONE_TO_FIVE_SECONDS, FIVE_TO_TEN_SECONDS, TEN_TO_THIRTY_SECONDS, OVER_THIRTY_SECONDS, UNKNOWN }
    enum Reach { HTTP_RESPONSE_RECEIVED, UNKNOWN }

    static ProviderFailureDiagnostic capture(Stage stage, Throwable failure, long startedNanos) {
        Kind kind=Kind.UNKNOWN; Integer status=null; int depth=0;Detail detail=Detail.UNSPECIFIED;
        // Bound traversal even if a third-party exception has a cyclic cause chain.
        for(Throwable cause=failure;cause!=null;) {
            if(cause instanceof com.google.genai.errors.ApiException api) {
                kind=Kind.HTTP;status=api.code()>=100&&api.code()<=599?api.code():null;
                if(api.code()==400)detail=invalidRequestDetail(api.message());break;
            }
            if(cause instanceof java.net.SocketTimeoutException)kind=Kind.SOCKET_TIMEOUT;
            else if(cause instanceof java.io.InterruptedIOException&&kind!=Kind.SOCKET_TIMEOUT)kind=Kind.INTERRUPTED_IO;
            else if(cause instanceof java.io.IOException&&kind==Kind.UNKNOWN)kind=Kind.TRANSPORT_IO;
            else if(cause instanceof IllegalArgumentException&&kind==Kind.UNKNOWN)kind=Kind.CLIENT_CONFIGURATION;
            Throwable next=cause.getCause();
            if(next==cause)break;
            cause=next;
            if(++depth>16)break;
        }
        return new ProviderFailureDiagnostic(stage,kind,status,band(startedNanos),
            status==null?Reach.UNKNOWN:Reach.HTTP_RESPONSE_RECEIVED,false,detail);
    }
    private static Detail invalidRequestDetail(String message) {
        // Inspect transient SDK text only for fixed parameter categories. Never
        // retain, echo, hash or export the message or any arbitrary substring.
        if(message==null||message.length()>4096)return Detail.UNSPECIFIED;
        String lower=message.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("thinking"))return Detail.REQUEST_THINKING_CONFIGURATION;
        if(lower.contains("schema")||lower.contains("enum"))return Detail.REQUEST_SCHEMA;
        if(lower.contains("video_metadata")||lower.contains("videometadata")||lower.contains("fps")||lower.contains("media_resolution"))return Detail.REQUEST_VIDEO_CONFIGURATION;
        if(lower.contains("mime"))return Detail.REQUEST_MEDIA_TYPE;
        if(lower.contains("model"))return Detail.REQUEST_MODEL;
        return Detail.UNSPECIFIED;
    }
    static Elapsed band(long startedNanos) {
        if(startedNanos==0)return Elapsed.UNKNOWN;
        long millis=Math.max(0,(System.nanoTime()-startedNanos)/1_000_000);
        return millis<1000?Elapsed.UNDER_ONE_SECOND:millis<5000?Elapsed.ONE_TO_FIVE_SECONDS:
            millis<10000?Elapsed.FIVE_TO_TEN_SECONDS:millis<30000?Elapsed.TEN_TO_THIRTY_SECONDS:Elapsed.OVER_THIRTY_SECONDS;
    }
}
