package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoAudioPlan;
import org.notesknowledge.websupport.ApiFailureException;

/** Native-only executable qualification against a frozen synthetic AAC tone.
 * No provider, user media, test framework or production entry point. */
public final class VideoSoundtrackRuntimeQualification {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Qualification root required");
        Path root=Path.of(args[0]);
        var mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Path scratch=Files.createTempDirectory("soundtrack-",mode),records=Files.createTempDirectory("soundtrack-records-",mode);
        String identity=SupervisedVideoClipRuntime.identity(root.resolve("helper"),root.resolve("runtime"),root.resolve("artifacts"),root.resolve("tools"));
        var selection=new MediaRuntimeProperties.Selection(true,"linux-confined-v1",root,records,scratch,identity,"ffmpeg-9.0.2-audiovisual-v2");
        byte[] original=Files.readAllBytes(root.resolve("tools/audio-preflight.mp4"));
        byte[] silent=Files.readAllBytes(root.resolve("tools/preflight.mp4"));
        int checks=0;
        // Invoke the actual configured production factory, including both startup probes.
        try(var runtime=new MediaRuntimeConfiguration().isolatedVideoClipRuntime(new MediaRuntimeProperties(null,selection))){
            var plan=runtime.audioPlan(original);
            if(!plan.hasAudio()||plan.durationSeconds()!=2||plan.samples()!=32000)throw failed();checks++;
            var video=runtime.plan(original);
            if(video.durations().size()!=50||video.tick(50)/(double)video.timescale()!=2)throw failed();checks++;
            try(var visual=runtime.render(original,video,0,50)){
                byte[] bytes=visual.bytes();
                try{if(runtime.audioPlan(bytes).hasAudio())throw failed();}finally{Arrays.fill(bytes,(byte)0);}
            }checks++;
            var sound=runtime.audio(original,plan);
            try(sound){
                byte[] pcm=sound.bytes();
                try{
                    if(pcm.length!=64044||!sound.plan().equals(plan))throw failed();
                    int crossings=0,previous=0;
                    for(int i=44;i<pcm.length;i+=2){int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));if(previous<0&&sample>=0)crossings++;previous=sample;}
                    if(crossings<1000||crossings>1100)throw failed();checks++;
                }finally{Arrays.fill(pcm,(byte)0);}
            }
            byte[] cleared=sound.bytes();for(byte value:cleared)if(value!=0)throw failed();checks++;
            if(runtime.audioPlan(silent).hasAudio())throw failed();checks++;
            denied(()->runtime.audio(silent,plan));checks++;
            denied(()->runtime.audio(original,new VideoAudioPlan(1000,1000,true)));checks++;
            denied(()->runtime.audioPlan(new byte[8]));checks++;
            Thread.currentThread().interrupt();denied(()->runtime.audioPlan(original));
            if(!Thread.interrupted())throw failed();checks++;
            try(var files=Files.list(scratch)){if(files.findAny().isPresent())throw failed();}checks++;
        }finally{Arrays.fill(original,(byte)0);Arrays.fill(silent,(byte)0);}
        try(var files=Files.list(scratch)){if(files.findAny().isPresent())throw failed();}
        Files.delete(scratch);Files.delete(records.resolve("worker.lock"));Files.delete(records);
        System.out.println("{\"passed\":true,\"checks\":"+checks+",\"nativeAacDecode\":true,\"pcmSamples\":32000,\"factoryPreflight\":true,\"providerCalls\":0}");
    }
    private static void denied(Runnable effect){try{effect.run();throw failed();}catch(ApiFailureException expected){}}
    private static AssertionError failed(){return new AssertionError("Native soundtrack qualification failed");}
}
