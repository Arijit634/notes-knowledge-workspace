package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit selection plus executable custody/containment preflight. No default
 * parser, automatic identity discovery, provider approval or Windows fallback. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaRuntimeProperties.class)
class MediaRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "notes.media-runtime.pdf", name = "enabled", havingValue = "true")
    SupervisedPdfPageRuntime isolatedPdfPageRuntime(MediaRuntimeProperties properties) throws IOException {
        var selection = properties.pdf();
        selection.requireLinuxProfile();
        var root = selection.root();
        try {
            return SupervisedPdfPageRuntime.linux(root.resolve("helper"), root.resolve("runtime"),
                    root.resolve("artifacts"), selection.artifactIdentity(), selection.recoveryDirectory(),
                    selection.version(), selection.artifactIdentity());
        } catch (IOException | RuntimeException failed) { throw unavailable(); }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "notes.media-runtime.video", name = "enabled", havingValue = "true")
    SupervisedVideoClipRuntime isolatedVideoClipRuntime(MediaRuntimeProperties properties) throws IOException {
        var selection = properties.video();
        selection.requireLinuxProfile();
        selection.requireVideoScratch();
        var root = selection.root();
        final SupervisedVideoClipRuntime candidate;
        try {
            candidate = SupervisedVideoClipRuntime.linux(root.resolve("helper"), root.resolve("runtime"),
                    root.resolve("artifacts"), root.resolve("tools"), selection.scratchDirectory(),
                    selection.recoveryDirectory(), selection.artifactIdentity(), selection.version());
        } catch (IOException | RuntimeException failed) { throw unavailable(); }
        byte[] original = null, audible = null;
        try {
            // Deployment-owned synthetic input is inside the pinned, immutable
            // tools tree. It is NOT user media or provider approval material.
            var fixture = root.resolve("tools/preflight.mp4");
            if (!Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(fixture) < 8 || Files.size(fixture) > 262_144) throw unavailable();
            try (var input = Files.newInputStream(fixture, LinkOption.NOFOLLOW_LINKS)) {
                original = input.readNBytes(262_145);
            }
            if (original.length > 262_144) throw unavailable();
            var plan = candidate.plan(original);
            if(candidate.audioPlan(original).hasAudio())throw unavailable();
            if (plan.tick(plan.durations().size()) > plan.timescale() * 2) throw unavailable();
            try (var child = candidate.render(original, plan, 0, plan.durations().size())) {
                if (!child.plan().equals(plan)) throw unavailable();
            }
            var audioFixture=root.resolve("tools/audio-preflight.mp4");
            if(!Files.isRegularFile(audioFixture,LinkOption.NOFOLLOW_LINKS)||Files.size(audioFixture)>262_144)throw unavailable();
            try(var input=Files.newInputStream(audioFixture,LinkOption.NOFOLLOW_LINKS)){audible=input.readNBytes(262_145);}
            if(audible.length<8||audible.length>262_144)throw unavailable();
            var audioPlan=candidate.audioPlan(audible);
            if(!audioPlan.hasAudio()||audioPlan.durationSeconds()>2)throw unavailable();
            try(var child=candidate.audio(audible,audioPlan)){
                byte[] pcm=child.bytes();
                try{
                    boolean nonzero=false;for(int i=44;i<pcm.length;i++)nonzero|=pcm[i]!=0;
                    if(!child.plan().equals(audioPlan)||!nonzero)throw unavailable();
                }finally{Arrays.fill(pcm,(byte)0);}
            }
            return candidate;
        } catch (Exception failed) {
            try { candidate.close(); } catch (IOException | RuntimeException uncertain) { throw unavailable(); }
            throw unavailable();
        } finally {
            if (original != null) Arrays.fill(original, (byte) 0);
            if (audible != null) Arrays.fill(audible, (byte) 0);
        }
    }
    private static IOException unavailable() { return new IOException("Media runtime preflight unavailable"); }
}
