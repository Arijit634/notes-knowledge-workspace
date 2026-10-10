package org.notesknowledge.knowledge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Local synthetic qualification only. Not application persistence or stored authorization.
 * Immutable, forced reservation events precede dispatch; unknown outcomes cannot be retried.
 * Cached results still require fresh production permits at every use. Never archive vectors.
 */
final class QualificationJournal implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path directory;
    private final FileChannel lockChannel;
    private final java.nio.channels.FileLock lock;
    private final List<Event> events=new ArrayList<>();
    record Event(int sequence,String key,String source,String kind,String state,String resultHash) { }
    QualificationJournal(Path directory,String identity) throws IOException {
        this.directory=directory;Files.createDirectories(directory);
        lockChannel=FileChannel.open(directory.resolve("lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockChannel.tryLock();if(lock==null)throw new IllegalStateException("Qualification already running");
        Path header=directory.resolve("identity.json");
        if(!Files.exists(header))forceCreate(header,JSON.writeValueAsBytes(Map.of("identity",identity,"historicalEmbeddingReservations",78,"historicalGenerationReservations",0)));
        try {
          var stored=JSON.readTree(Files.readAllBytes(header));
          if(!stored.get("identity").asText().equals(identity)||stored.get("historicalEmbeddingReservations").asInt()!=78)
              throw new IllegalStateException("Qualification identity mismatch");
          try(var paths=Files.list(directory)) {
            for(Path path:paths.filter(p->p.getFileName().toString().matches("[0-9]{6}\\.json")).sorted().toList()) {
                Event e=JSON.readValue(Files.readAllBytes(path),Event.class);
                if(e.sequence()!=events.size()+1||!path.getFileName().toString().equals("%06d.json".formatted(e.sequence())))
                    throw new IllegalStateException("Incomplete qualification journal");
                events.add(e);
            }
          }
        } catch(IOException|RuntimeException failure) {
            lock.release();lockChannel.close();throw failure;
        }
    }
    synchronized Event reserve(String key,String source,String kind) throws IOException {
        if(!Set.of("embedding","generation").contains(kind)||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid request metadata");
        if(events.stream().anyMatch(e->e.key().equals(key)))throw new IllegalStateException("Request already reserved; uncertain calls never automatically retried");
        if(reservations(kind)>=(kind.equals("embedding")?120:8))throw new IllegalStateException("Qualification budget exhausted");
        Event event=new Event(events.size()+1,key,source,kind,"reserved","");append(event);return event;
    }
    synchronized void complete(Event reservation,Object result) throws IOException {
        if(events.stream().noneMatch(reservation::equals)||!reservation.state().equals("reserved")||latest(reservation.key())!=reservation)
            throw new IllegalStateException("Reservation not current");
        byte[] bytes=JSON.writeValueAsBytes(result);
        forceCreate(directory.resolve(reservation.key()+".result"),bytes);
        append(new Event(events.size()+1,reservation.key(),reservation.source(),reservation.kind(),"completed",hash(bytes)));
    }
    synchronized <T> T completed(String key,Class<T> type) throws IOException {
        Event event=latest(key);if(event==null)return null;
        if(!event.state().equals("completed"))throw new IllegalStateException("Uncertain request; stop for review");
        byte[] bytes=Files.readAllBytes(directory.resolve(key+".result"));
        if(!event.resultHash().equals(hash(bytes)))throw new IllegalStateException("Qualification result integrity failure");
        return JSON.readValue(bytes,type);
    }
    synchronized int reservations(String kind){return (int)events.stream().filter(e->e.kind().equals(kind)&&e.state().equals("reserved")).count();}
    synchronized int uncertainReservations(){return (int)events.stream().filter(e->e.state().equals("reserved")&&latest(e.key()).state().equals("reserved")).count();}
    synchronized List<Map<String,Object>> sanitizedLedger() {
        return events.stream().map(e->Map.<String,Object>of("sequence",e.sequence(),"source",e.source(),"kind",e.kind(),"state",e.state())).toList();
    }
    private Event latest(String key){return events.reversed().stream().filter(e->e.key().equals(key)).findFirst().orElse(null);}
    private void append(Event event)throws IOException {
        forceCreate(directory.resolve("%06d.json".formatted(event.sequence())),JSON.writeValueAsBytes(event));events.add(event);
    }
    static void forceCreate(Path path,byte[] value)throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var bytes=ByteBuffer.wrap(value);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
    }
    static String hash(String text){return hash(text.getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public void close()throws IOException{if(lock.isValid())lock.release();if(lockChannel.isOpen())lockChannel.close();}
}
