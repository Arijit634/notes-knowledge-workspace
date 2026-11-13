package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Explicit managed-host alternative: immutable root-owned deployment inputs,
 * inherited hard UID-wide process limit and kernel write denial. The qualified
 * image entry point installs Landlock/seccomp before app, proxy or cache starts.
 * This is not a fallback selected merely because a host safety check failed. */
final class ManagedMediaBoundary {
    private ManagedMediaBoundary() { }
    static boolean selected() {
        return "landlock-managed-v1".equals(System.getProperty("nkw.media.service-boundary"));
    }
    static boolean verified() throws IOException {
        if(!selected())return false;
        try {
            if(!"Linux".equals(System.getProperty("os.name"))||!"amd64".equals(System.getProperty("os.arch")))throw unavailable();
            String status=Files.readString(Path.of("/proc/self/status"));
            String limits=Files.readString(Path.of("/proc/self/limits"));
            if(!processProtected(status,limits))throw unavailable();
            Path probe=Path.of("/provenance/service-write-probe");
            PdfRuntimeIdentity.noLinks(probe);
            for(Path parent=probe.getParent();parent!=null;parent=parent.getParent()) {
                var directory=Files.readAttributes(parent,"unix:uid,mode,isDirectory",LinkOption.NOFOLLOW_LINKS);
                if(((Number)directory.get("uid")).longValue()!=0||(((Number)directory.get("mode")).intValue()&0022)!=0
                        ||!Boolean.TRUE.equals(directory.get("isDirectory")))throw unavailable();
            }
            var facts=Files.readAttributes(probe,"unix:uid,mode,nlink,isRegularFile",LinkOption.NOFOLLOW_LINKS);
            if(((Number)facts.get("uid")).longValue()!=0||((Number)facts.get("mode")).intValue()%4096!=0666
                    ||((Number)facts.get("nlink")).longValue()!=1||!Boolean.TRUE.equals(facts.get("isRegularFile"))
                    ||Files.size(probe)!=1)throw unavailable();
            // Open without TRUNCATE: the negative probe never changes bytes,
            // even if an operator accidentally omitted the native entry point.
            try(var unexpected=Files.newByteChannel(probe,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable();
            } catch(AccessDeniedException denied) { return true; }
            catch(IOException denied) {
                if(Files.getFileStore(probe).isReadOnly())return true;
                throw denied;
            }
        } catch(IOException|RuntimeException denied) { throw unavailable(); }
    }
    static boolean processProtected(String status,String limits) {
        if(status==null||limits==null||status.length()>65536||limits.length()>16384)return false;
        if(!java.util.regex.Pattern.compile("(?m)^NoNewPrivs:\\s*1\\s*$").matcher(status).find())return false;
        for(String field:java.util.List.of("CapInh","CapPrm","CapEff","CapAmb"))
            if(!java.util.regex.Pattern.compile("(?m)^"+field+":\\s*0{16}\\s*$").matcher(status).find())return false;
        return java.util.regex.Pattern.compile("(?m)^Max processes\\s+300\\s+300\\s+processes\\s*$").matcher(limits).find();
    }
    private static IOException unavailable(){return new IOException("Managed media service boundary unavailable");}
}
