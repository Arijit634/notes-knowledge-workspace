package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HexFormat;

/** Bounded local crash bookkeeping, never work authority or media storage.
 * Linux PDEATHSIG/Windows Job custody owns termination. Recovery NEVER kills a
 * PID from disk: reused PIDs must not become authority over unrelated processes.
 * A still-live matching attempt prevents reuse until termination is established.
 */
final class PdfWorkerAttemptStore implements AutoCloseable {
    private static final int MAX_RECORDS=64;
    private static final String MAGIC="nkw-pdf-attempt-v1";
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    PdfWorkerAttemptStore(Path directory)throws IOException {
        PdfRuntimeIdentity.noLinks(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)
                ||!Files.getFileStore(directory).supportsFileAttributeView("posix"))throw unavailable();
        root=directory.toAbsolutePath();
        requirePrivate(root,true);
        FileChannel opened=null;FileLock acquired=null;
        try {
            Path lockPath=root.resolve("worker.lock");
            if(!Files.exists(lockPath,LinkOption.NOFOLLOW_LINKS))
                Files.createFile(lockPath,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            requirePrivate(lockPath,false);
            opened=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try{acquired=opened.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){throw unavailable();}
            if(acquired==null)throw unavailable();
            lockChannel=opened;lock=acquired;
            recover();
        }catch(IOException|RuntimeException failure){
            if(acquired!=null)acquired.release();if(opened!=null)opened.close();
            throw unavailable();
        }
    }

    synchronized Attempt record(Process child)throws IOException {
        requireOpen();requirePrivate(root,true);
        try(var files=Files.list(root)){if(files.limit(MAX_RECORDS+2L).count()>=MAX_RECORDS+1L)throw unavailable();}
        // No source ID, filename, media, digest, provider information or lease token
        // enters this record. Creation identity is only a fail-closed liveness test.
        Instant started=child.info().startInstant().orElseThrow(PdfWorkerAttemptStore::unavailable);
        if(!child.isAlive())throw unavailable();
        byte[] nonce=new byte[16];new java.security.SecureRandom().nextBytes(nonce);
        String name="attempt-"+HexFormat.of().formatHex(nonce);
        Path pending=root.resolve(name+".pending"),path=root.resolve(name+".state");
        byte[] body=(MAGIC+"\n"+child.pid()+"\n"+started.toEpochMilli()+"\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try(var channel=FileChannel.open(pending,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
            ByteBuffer buffer=ByteBuffer.wrap(body);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        // No partially written PID record is published as complete recovery
        // state. An interrupted pre-publication record is disposable metadata;
        // parent-death custody, not this file, terminates its process.
        Files.move(pending,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
        return new Attempt(path,child);
    }

    private void recover()throws IOException {
        java.util.List<Path> entries;
        try(var files=Files.list(root)){entries=files.limit(MAX_RECORDS+2L).toList();}
        if(entries.size()>MAX_RECORDS+1)throw unavailable();
        // Validate the entire bounded inventory before removing any stale record.
        var stale=new java.util.ArrayList<Path>();
        for(Path entry:entries){
            String name=entry.getFileName().toString();
            requirePrivate(entry,false);
            if(name.equals("worker.lock"))continue;
            if(Files.size(entry)>128)throw unavailable();
            if(name.matches("attempt-[0-9a-f]{32}\\.pending")){stale.add(entry);continue;}
            if(!name.matches("attempt-[0-9a-f]{32}\\.state"))throw unavailable();
            byte[] body;
            try(var input=Files.newInputStream(entry,LinkOption.NOFOLLOW_LINKS)){body=input.readNBytes(129);}
            if(body.length>128)throw unavailable();
            String text=new String(body,java.nio.charset.StandardCharsets.US_ASCII);
            if(!text.matches(MAGIC+"\n[1-9][0-9]{0,18}\n[1-9][0-9]{0,18}\n"))throw unavailable();
            String[] fields=text.split("\n");
            long pid,start;
            try{pid=Long.parseLong(fields[1]);start=Long.parseLong(fields[2]);}
            catch(NumberFormatException invalid){throw unavailable();}
            var handle=ProcessHandle.of(pid);
            if(handle.isPresent()&&handle.get().isAlive()){
                var actual=handle.get().info().startInstant();
                // Unknown creation identity also denies cleanup; never guess.
                if(actual.isEmpty()||actual.get().toEpochMilli()==start)throw unavailable();
            }
            stale.add(entry);
        }
        for(Path entry:stale)Files.delete(entry);
        if(!stale.isEmpty())syncDirectory();
    }

    private static void requirePrivate(Path path,boolean directory)throws IOException {
        PdfRuntimeIdentity.noLinks(path);
        var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(!permissions.equals(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"))
                ||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(Path.of("/proc/self"))))throw unavailable();
        if(!directory&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                ||((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1))throw unavailable();
    }
    private void syncDirectory()throws IOException {
        try(var channel=FileChannel.open(root,StandardOpenOption.READ)){channel.force(true);}
    }
    private void requireOpen()throws IOException{if(closed||!lock.isValid())throw unavailable();}
    @Override public synchronized void close()throws IOException {
        if(closed)return;closed=true;
        try{lock.release();}finally{lockChannel.close();}
    }
    final class Attempt implements AutoCloseable {
        private final Path path;private final Process child;private boolean removed;
        private Attempt(Path path,Process child){this.path=path;this.child=child;}
        @Override public void close()throws IOException {
            synchronized(PdfWorkerAttemptStore.this){
                if(removed)return;requireOpen();
                if(child.isAlive())throw unavailable();
                requirePrivate(path,false);Files.delete(path);syncDirectory();removed=true;
            }
        }
    }
    private static IOException unavailable(){return new IOException("PDF worker recovery unavailable");}
}
