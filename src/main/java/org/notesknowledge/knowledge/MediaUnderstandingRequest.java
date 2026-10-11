package org.notesknowledge.knowledge;

/** Server-owned processing scope, not authorization. Time locations are relative to these bytes. */
record MediaUnderstandingRequest(String mediaType,Integer firstPage,Integer lastPage,Double durationSeconds) {
    static final int PDF_BATCH_PAGES=8;
    static final int MAX_INLINE_REQUEST_BYTES=18_000_000;
    static final int ENVELOPE_RESERVE_BYTES=65_536;
    static final int MAX_DIRECT_VIDEO_SECONDS=30;

    MediaUnderstandingRequest {
        if(mediaType==null||!java.util.Set.of("image/png","image/jpeg","audio/wav","video/mp4","application/pdf").contains(mediaType)
                ||(firstPage==null)!=(lastPage==null)
                ||mediaType.equals("application/pdf")!=(firstPage!=null)
                ||firstPage!=null&&(firstPage<1||lastPage<firstPage||lastPage>100||lastPage-firstPage>=PDF_BATCH_PAGES)
                ||java.util.Set.of("audio/wav","video/mp4").contains(mediaType)!=(durationSeconds!=null)
                ||durationSeconds!=null&&(!Double.isFinite(durationSeconds)||durationSeconds<=0||durationSeconds>300))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
    }
    void requireInlineBudget(int bytes,String instruction,String schema) {
        // Fixed options/model/MIME/scope keys are bounded well below 64 KiB. Count
        // Base64, not raw bytes; leave another 2 MB below the 20 MB guidance.
        long serializedBound=4L*((bytes+2L)/3)+ENVELOPE_RESERVE_BYTES
            +instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            +schema.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes<=0||serializedBound>=MAX_INLINE_REQUEST_BYTES
                ||mediaType.equals("video/mp4")&&durationSeconds>MAX_DIRECT_VIDEO_SECONDS)
            throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
    }
    @Override public String toString(){return "MediaUnderstandingRequest[scope only]";}
}
