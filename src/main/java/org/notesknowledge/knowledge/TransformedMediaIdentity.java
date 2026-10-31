package org.notesknowledge.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;

/** Exact transient child identity. Neither this value nor its fingerprint is authorization. */
record TransformedMediaIdentity(PrivateAiSourceCurrentness.Expected original, String originalSha256,
        long originalBytes, String originalType, String profile, String implementationVersion,
        String binarySha256, String configurationSha256, String childSha256, long childBytes,
        String childType, Scope scope, String lineage, String provider, String model, String task,
        String policySha256) {
    record Scope(String kind, long first, long last, long timescale, long startTick, long endTick) {
        Scope {
            boolean pdf="pdf_pages".equals(kind) && first>=1 && last>=first && last<=100
                && timescale==0 && startTick==0 && endTick==0;
            boolean video="video_samples".equals(kind) && first>=0 && last>first && last<=18_000
                && timescale>0 && timescale<=0xffff_ffffL && startTick>=0 && endTick>startTick
                && endTick<=300L*timescale;
            boolean soundtrack="video_audio".equals(kind)&&first==0&&last>0&&last<=4_800_000
                &&timescale==16000&&startTick==0&&endTick==last;
            if(!pdf&&!video&&!soundtrack)invalid();
        }
        @Override public String toString(){return "TransformedMediaScope[REDACTED]";}
    }
    TransformedMediaIdentity {
        if(original==null||original.attachmentId()==null||original.attachmentGeneration()==null
                ||originalBytes<=0||originalBytes>25L*1024*1024||childBytes<=0||childBytes>=18_000_000
                ||scope==null||!hash(originalSha256)||!hash(binarySha256)||!hash(configurationSha256)
                ||!hash(childSha256)||!hash(lineage)||!hash(policySha256)
                ||!name(profile)||!name(implementationVersion)||!name(provider)||!name(model)
                ||!"media-understanding".equals(task))invalid();
        boolean pdf="application/pdf".equals(originalType)&&"pdf_pages".equals(scope.kind())
            &&("application/pdf".equals(childType)||"image/png".equals(childType));
        boolean video="video/mp4".equals(originalType)&&"video_samples".equals(scope.kind())&&"video/mp4".equals(childType);
        boolean soundtrack="video/mp4".equals(originalType)&&"video_audio".equals(scope.kind())&&"audio/wav".equals(childType)
            &&org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioChild.PROFILE.equals(profile)&&childBytes==44+2*scope.last();
        if(!pdf&&!video&&!soundtrack)invalid();
    }
    static String digest(byte[] bytes) {
        if(bytes==null)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    void requireBytes(byte[] child) {
        if(child==null||child.length!=childBytes||!digest(child).equals(childSha256))invalid();
    }
    void requireOriginalBytes(byte[] bytes) {
        if(bytes==null||bytes.length!=originalBytes||!digest(bytes).equals(originalSha256))invalid();
    }
    String approvalFingerprint() {
        // Every field is length-delimited UTF-8; fixed order, no implicit normalization.
        var fields=List.of("nkw-transformed-child-v1",original.owner().toString(),original.noteId().toString(),
            original.attachmentId().toString(),Long.toString(original.revision()),Long.toString(original.aiGeneration()),
            original.attachmentGeneration().toString(),originalSha256,Long.toString(originalBytes),originalType,
            profile,implementationVersion,binarySha256,configurationSha256,childSha256,Long.toString(childBytes),
            childType,scope.kind(),Long.toString(scope.first()),Long.toString(scope.last()),Long.toString(scope.timescale()),
            Long.toString(scope.startTick()),Long.toString(scope.endTick()),lineage,provider,model,task,policySha256);
        try(var buffer=new ByteArrayOutputStream();var output=new DataOutputStream(buffer)) {
            for(var field:fields){var encoded=field.getBytes(StandardCharsets.UTF_8);output.writeInt(encoded.length);output.write(encoded);}
            return digest(buffer.toByteArray());
        }catch(java.io.IOException impossible){throw new IllegalStateException("In-memory encoding unavailable");}
    }
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean name(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}");}
    private static void invalid(){throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);}
    @Override public String toString(){return "TransformedMediaIdentity[REDACTED]";}
}
