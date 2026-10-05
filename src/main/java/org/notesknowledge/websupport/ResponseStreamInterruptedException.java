package org.notesknowledge.websupport;

/** Signals already-committed binary output: no JSON error can safely be appended. */
public final class ResponseStreamInterruptedException extends RuntimeException {
    static final String REQUEST_ATTRIBUTE = ResponseStreamInterruptedException.class.getName() + ".interrupted";
    public ResponseStreamInterruptedException() { super("response_stream_interrupted"); }
}
