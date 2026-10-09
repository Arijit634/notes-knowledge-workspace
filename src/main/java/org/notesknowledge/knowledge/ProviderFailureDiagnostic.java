package org.notesknowledge.knowledge;

/** Bounded metadata only: never retain a provider exception, message, body or request. */
record ProviderFailureDiagnostic(Stage stage, Kind kind, Integer httpStatus,
        Elapsed elapsed, Reach providerReached, boolean structurallyValidResponse) {
    enum Stage { EMBEDDING, GENERATION, MEDIA }
    enum Kind { HTTP, SOCKET_TIMEOUT, INTERRUPTED_IO, TRANSPORT_IO, CLIENT_CONFIGURATION, PARSING, UNKNOWN }
    enum Elapsed { UNDER_ONE_SECOND, ONE_TO_FIVE_SECONDS, FIVE_TO_TEN_SECONDS, TEN_TO_THIRTY_SECONDS, OVER_THIRTY_SECONDS, UNKNOWN }
    enum Reach { HTTP_RESPONSE_RECEIVED, UNKNOWN }

    static ProviderFailureDiagnostic capture(Stage stage, Throwable failure, long startedNanos) {
        Kind kind=Kind.UNKNOWN; Integer status=null; int depth=0;
        // Bound traversal even if a third-party exception has a cyclic cause chain.
        for(Throwable cause=failure;cause!=null;) {
            if(cause instanceof com.google.genai.errors.ApiException api) {
                kind=Kind.HTTP;status=api.code()>=100&&api.code()<=599?api.code():null;break;
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
            status==null?Reach.UNKNOWN:Reach.HTTP_RESPONSE_RECEIVED,false);
    }
    static Elapsed band(long startedNanos) {
        if(startedNanos==0)return Elapsed.UNKNOWN;
        long millis=Math.max(0,(System.nanoTime()-startedNanos)/1_000_000);
        return millis<1000?Elapsed.UNDER_ONE_SECOND:millis<5000?Elapsed.ONE_TO_FIVE_SECONDS:
            millis<10000?Elapsed.FIVE_TO_TEN_SECONDS:millis<30000?Elapsed.TEN_TO_THIRTY_SECONDS:Elapsed.OVER_THIRTY_SECONDS;
    }
}
