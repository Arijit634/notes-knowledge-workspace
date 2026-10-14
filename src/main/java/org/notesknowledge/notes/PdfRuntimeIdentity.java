package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Bounded identity of trusted deployment artifacts, not media authorization or
 * OS qualification. Never log this fingerprint. Configuration must pin it before
 * execution; the resulting child still needs independent exact-byte approval.
 */
final class PdfRuntimeIdentity {
    private static final long MAX_BYTES=512L*1024*1024;
    private static final int MAX_FILES=4096;
    private PdfRuntimeIdentity(){ }

    static String fingerprint(Path runtime,Path artifacts,Path helper)throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            field(digest,"nkw-pdf-runtime-artifacts-v1");
            field(digest,PdfPageRasterWorker.PROFILE);
            field(digest,PdfPageRasterWorker.CONFIGURATION);
            var budget=new Budget();
            tree(digest,"runtime",runtime,budget);
            tree(digest,"artifacts",artifacts,budget);
            if(helper!=null){field(digest,"helper");file(digest,helper,budget);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }

    static void require(String expected,Path runtime,Path artifacts,Path helper)throws IOException {
        if(expected==null||!expected.matches("[0-9a-f]{64}"))throw unavailable();
        if(!MessageDigest.isEqual(HexFormat.of().parseHex(expected),
                HexFormat.of().parseHex(fingerprint(runtime,artifacts,helper))))throw unavailable();
    }

    static void noLinks(Path path)throws IOException {
        Path absolute=path.toAbsolutePath();
        if(!absolute.equals(absolute.normalize()))throw unavailable();
        for(Path current=absolute;current!=null;current=current.getParent()){
            var facts=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(facts.isSymbolicLink()||facts.isOther())throw unavailable();
        }
    }

    private static void tree(MessageDigest digest,String label,Path root,Budget budget)throws IOException {
        noLinks(root);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw unavailable();
        List<Path> entries;
        try(var tree=Files.walk(root,32)){
            entries=tree.limit(MAX_FILES+2L).sorted(java.util.Comparator.comparing(
                    path->root.relativize(path).toString().replace('\\','/'))).toList();
        }
        if(entries.size()>MAX_FILES)throw unavailable();
        field(digest,label);
        field(digest,Integer.toString(entries.size()));
        for(Path entry:entries){
            var facts=Files.readAttributes(entry,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(facts.isSymbolicLink()||facts.isOther())throw unavailable();
            field(digest,root.relativize(entry).toString().replace('\\','/'));
            if(facts.isDirectory()){
                if(!entry.equals(root)&&root.relativize(entry).getNameCount()>=32)throw unavailable();
                field(digest,"directory");
            }
            else if(facts.isRegularFile()){field(digest,"file");file(digest,entry,budget);}
            else throw unavailable();
        }
    }

    private static void file(MessageDigest digest,Path file,Budget budget)throws IOException {
        noLinks(file);
        var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||++budget.files>MAX_FILES||before.size()>MAX_BYTES-budget.bytes)throw unavailable();
        budget.bytes+=before.size();
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(before.size()).array());
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[8192];long count=0;
            for(int read;(read=input.read(buffer))!=-1;){
                count+=read;if(count>before.size())throw unavailable();digest.update(buffer,0,read);
            }
            if(count!=before.size())throw unavailable();
        }
        var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())
                ||!java.util.Objects.equals(before.fileKey(),after.fileKey()))throw unavailable();
    }

    private static void field(MessageDigest digest,String value){
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());digest.update(bytes);
    }
    private static final class Budget {long bytes;int files;}
    private static IOException unavailable(){return new IOException("PDF runtime identity unavailable");}
}
