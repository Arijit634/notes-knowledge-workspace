package org.notesknowledge.notes;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collections;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoChild;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Mandatory CI uses real decodable synthetic IDR bytes with a controlled transform.
 * An explicit native qualification property instead exercises the actual sandboxed encoder.
 * Neither mode is a production registration or a live-model quality claim.
 */
@TestConfiguration(proxyBeanMethods=false)
public class VideoClipRuntimeFixtures {
    private static SupervisedVideoClipRuntime nativeRuntime;
    private static Path scratch,records;
    @Bean VideoClipRuntime videoFixture() {
        return new VideoClipRuntime() {
            public VideoPlan plan(byte[] original){return VideoClipRuntimeFixtures.plan(original);}
            public VideoChild render(byte[] original,VideoPlan plan,int first,int last){return VideoClipRuntimeFixtures.render(original,plan,first,last);}
        };
    }
    public static byte[] original()throws Exception{return MeaningfulMediaFixtures.videoSamples(20,2000);}
    public static VideoPlan plan(byte[] original) {
        try {
            var nativeValue=nativeRuntime();
            if(nativeValue!=null)return nativeValue.plan(original);
            if(!java.util.Arrays.equals(original,original()))throw new IllegalArgumentException("Unexpected synthetic input");
            return new VideoPlan(1000,Collections.nCopies(20,2000L));
        }catch(Exception failed){throw new AssertionError("Synthetic video fixture unavailable",failed);}
    }
    public static VideoChild render(byte[] original,VideoPlan plan,int first,int last) {
        try {
            var nativeValue=nativeRuntime();if(nativeValue!=null)return nativeValue.render(original,plan,first,last);
            return new VideoChild(plan,first,last,MeaningfulMediaFixtures.videoSamples(last-first,2000),SupervisedVideoClipRuntime.PROFILE,
                "controlled-fixture-v1","b".repeat(64),"c".repeat(64));
        }catch(Exception failed){throw new AssertionError("Synthetic video fixture unavailable",failed);}
    }
    public static String fingerprint() {
        try(var child=render(original(),plan(original()),0,10)){return child.transformFingerprint();}
        catch(Exception failed){throw new AssertionError("Synthetic profile unavailable",failed);}
    }
    private static synchronized SupervisedVideoClipRuntime nativeRuntime()throws Exception {
        String configured=System.getProperty("nkw.test.video.nativeRoot");if(configured==null)return null;
        if(nativeRuntime==null){
            Path root=Path.of(configured);
            scratch=Files.createTempDirectory("video-pipeline-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            records=Files.createTempDirectory("video-pipeline-records-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            var helper=root.resolve("helper");var runtime=root.resolve("runtime");var artifacts=root.resolve("artifacts");var tools=root.resolve("tools");
            nativeRuntime=SupervisedVideoClipRuntime.linux(helper,runtime,artifacts,tools,scratch,records,
                SupervisedVideoClipRuntime.identity(helper,runtime,artifacts,tools),"ffmpeg-9.0.2-profile-v1");
        }
        return nativeRuntime;
    }
    public static synchronized void closeNative()throws Exception {
        if(nativeRuntime!=null){nativeRuntime.close();nativeRuntime=null;
            try(var files=Files.list(scratch)){if(files.findAny().isPresent())throw new AssertionError("Video scratch leaked");}
            try(var files=Files.list(records)){if(files.count()!=1)throw new AssertionError("Video journal leaked");}
            Files.delete(records.resolve("worker.lock"));Files.delete(records);Files.delete(scratch);
        }
    }
}
