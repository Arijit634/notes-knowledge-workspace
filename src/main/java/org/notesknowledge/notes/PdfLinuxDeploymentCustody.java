package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Read-only, administrator-owned deployment inputs. Hashing alone cannot close
 * replacement races. The unprivileged application/worker must be unable to edit
 * any inode OR rename any ancestor between verification and dynamic loading.
 * Host administrators and mount-namespace administrators remain trusted; this
 * gate is not a defense against a compromised host root or writable alias held
 * by that trusted deployment authority. No chmod/chown/mount is performed here.
 */
final class PdfLinuxDeploymentCustody {
    private PdfLinuxDeploymentCustody(){ }
    static void require(Path helper,Path runtime,Path artifacts)throws IOException {
        if(!System.getProperty("os.name").equals("Linux"))throw unavailable();
        if(((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).longValue()==0)throw unavailable();
        // Helpers enforce no Linux capabilities before parsing. The parent must
        // also lack mount/ownership escape authority; inspect fixed proc metadata.
        String status=Files.readString(Path.of("/proc/self/status"));
        for(String field:java.util.List.of("CapInh","CapPrm","CapEff","CapAmb")){
            if(!java.util.regex.Pattern.compile("(?m)^"+field+":\\s*0{16}\\s*$").matcher(status).find())throw unavailable();
        }
        for(Path root:java.util.List.of(runtime,artifacts)){
            ancestors(root);
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw unavailable();
            try(var walk=Files.walk(root,32)){
                var entries=walk.limit(4097).toList();if(entries.size()>4096)throw unavailable();
                for(Path entry:entries){immutable(entry);if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)
                        &&!entry.equals(root)&&root.relativize(entry).getNameCount()>=32)throw unavailable();}
            }
        }
        ancestors(helper);immutable(helper);
        if(!Files.isRegularFile(helper,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(helper))throw unavailable();
        // The fixed loader/library/font trees are part of trusted deployment,
        // not attacker-writable search paths selected by PDF input.
        for(String fixed:java.util.List.of("/usr/lib","/usr/lib64","/lib","/lib64","/usr/share/fonts","/usr/share/fontconfig","/etc/fonts")){
            Path path=Path.of(fixed);if(Files.exists(path))systemTree(path.toRealPath());
        }
        Path loader=Path.of(System.getProperty("os.arch").equals("aarch64")?
                "/lib/ld-linux-aarch64.so.1":"/lib64/ld-linux-x86-64.so.2").toRealPath();
        ancestors(loader);
        Path cache=Path.of("/etc/ld.so.cache");if(Files.exists(cache))ancestors(cache);
    }
    private static void systemTree(Path root)throws IOException {
        ancestors(root);
        // No nested writable mount or mutable loader/font dependency is allowed.
        // Distribution-managed links are allowed ONLY in these fixed OS trees,
        // and their resolved destinations and ancestors must also be immutable.
        try(var walk=Files.walk(root,32)){
            var entries=walk.limit(32769).toList();if(entries.size()>32768)throw unavailable();
            for(Path entry:entries){
                if(Files.isSymbolicLink(entry))ancestors(entry.toRealPath());
                else immutable(entry);
                if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)&&!entry.equals(root)
                        &&root.relativize(entry).getNameCount()>=32)throw unavailable();
            }
        }
    }
    private static void ancestors(Path path)throws IOException {
        PdfRuntimeIdentity.noLinks(path);
        for(Path current=path.toAbsolutePath();current!=null;current=current.getParent())immutable(current);
    }
    private static void immutable(Path path)throws IOException {
        var facts=Files.readAttributes(path,"unix:uid,mode,nlink,isRegularFile,isDirectory,isSymbolicLink",LinkOption.NOFOLLOW_LINKS);
        int mode=((Number)facts.get("mode")).intValue();
        if(((Number)facts.get("uid")).longValue()!=0||(mode&0022)!=0||(mode&06000)!=0
                ||Boolean.TRUE.equals(facts.get("isSymbolicLink"))
                ||!Boolean.TRUE.equals(facts.get("isRegularFile"))&&!Boolean.TRUE.equals(facts.get("isDirectory"))
                ||Boolean.TRUE.equals(facts.get("isRegularFile"))&&((Number)facts.get("nlink")).longValue()!=1
                ||!Files.getFileStore(path).isReadOnly())throw unavailable();
    }
    private static IOException unavailable(){return new IOException("PDF deployment custody unavailable");}
}
