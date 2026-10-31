package org.notesknowledge.notes;

/** Fixed isolated entry point. No paths, filenames or diagnostics cross its pipes. */
final class VideoTimelineWorker {
    public static void main(String[] args) {
        if(args.length!=1||!java.util.Set.of("upload","canonical","audio").contains(args[0]))System.exit(2);
        try {
            // The launcher exposes only this fixed, read-only, bounded source file.
            var validator=new StrictMp4StructureValidator();var path=java.nio.file.Path.of("input.mp4");
            var out=new java.io.DataOutputStream(System.out);
            if(args[0].equals("audio")) {
                var audio=validator.uploadTimeline(path).audio();out.writeInt(0x4e4b5641);
                out.writeLong(audio.timescale());out.writeLong(audio.ticks());out.writeBoolean(audio.hasAudio());out.flush();return;
            }
            var timeline=args[0].equals("upload")?validator.uploadTimeline(path).video():validator.timeline(path);
            out.writeInt(0x4e4b5654);out.writeLong(timeline.timescale());
            long[] durations=timeline.durations();out.writeInt(durations.length);
            for(long duration:durations)out.writeLong(duration);
            out.flush();
        }catch(Exception invalid){System.exit(2);}
    }
}
