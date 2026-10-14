package org.notesknowledge.notes;

/** Fixed isolated entry point. No paths, filenames or diagnostics cross its pipes. */
final class VideoTimelineWorker {
    public static void main(String[] args) {
        if(args.length!=0)System.exit(2);
        try {
            // The launcher exposes only this fixed, read-only, bounded source file.
            var timeline=new StrictMp4StructureValidator().timeline(java.nio.file.Path.of("input.mp4"));
            var out=new java.io.DataOutputStream(System.out);
            out.writeInt(0x4e4b5654);out.writeLong(timeline.timescale());
            long[] durations=timeline.durations();out.writeInt(durations.length);
            for(long duration:durations)out.writeLong(duration);
            out.flush();
        }catch(Exception invalid){System.exit(2);}
    }
}
