package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** RLIMIT_NPROC counts all threads sharing the real service UID. Reserve at
 * least half of the helper's 256-task ceiling before admission. This is not a
 * per-worker quota and does not assume an otherwise idle/shared service UID.
 * Concurrent unrelated service activity can cause safe startup denial; it can
 * never raise the child's hard ceiling. Deploy with a dedicated service UID.
 */
final class PdfLinuxServiceBudget {
    private PdfLinuxServiceBudget(){ }
    static void require()throws IOException {
        long uid=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue();
        if(uid==0)throw unavailable();
        int threads=0;
        try(var paths=Files.list(Path.of("/proc"))){
            var entries=paths.limit(8193).toList();if(entries.size()>8192)throw unavailable();
            for(Path entry:entries){
                if(!entry.getFileName().toString().matches("[1-9][0-9]{0,18}"))continue;
                try {
                    if(((Number)Files.getAttribute(entry,"unix:uid")).longValue()!=uid)continue;
                    try(var tasks=Files.list(entry.resolve("task"))){
                        threads+=Math.toIntExact(tasks.limit(129).count());
                        if(threads>128)throw unavailable();
                    }
                }catch(java.nio.file.NoSuchFileException exited){ /* Process exited during bounded inventory. */ }
            }
        }
        if(threads<1)throw unavailable();
    }
    private static IOException unavailable(){return new IOException("PDF service budget unavailable");}
}
