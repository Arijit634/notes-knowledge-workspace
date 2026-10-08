package org.notesknowledge.notes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestConfiguration(proxyBeanMethods=false)
public class PublicationAttachmentFixtures {
    @Bean @Primary public SourceObjects publicationSourceObjects(){return new SourceObjects();}
    @Bean public RetentionFixture publicationRetentionFixture(NotesRepository notes,NoteVersionRepository versions){return new RetentionFixture(notes,versions);}
    public static class RetentionFixture {
        private final NotesRepository notes;private final NoteVersionRepository versions;
        RetentionFixture(NotesRepository notes,NoteVersionRepository versions){this.notes=notes;this.versions=versions;}
        public int compact(java.util.UUID owner,java.util.UUID note,java.util.UUID held) {
            notes.lock(owner,note).orElseThrow();return versions.compact(owner,note,2,List.of(held));
        }
    }
    public static class SourceObjects implements PrivateAttachmentObjectStore {
        public final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();
        public int opens;
        private void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Storage inside transaction");}
        public InputStream openRange(String ref,long offset,long length){outside();opens++;var b=bytes.get(ref);return b==null?null:new ByteArrayInputStream(b,(int)offset,(int)length);}
        public void write(String ref,InputStream input,long size){outside();try{if(bytes.putIfAbsent(ref,input.readNBytes(Math.toIntExact(size)))!=null)throw new AssertionError("Not create-only");}catch(java.io.IOException e){throw new AssertionError(e);}}
        public void delete(String ref){outside();bytes.remove(ref);}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return List.of();}
    }
}
