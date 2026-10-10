package org.notesknowledge.notes;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit local synthetic harness custody. No production configuration or object-store fallback. */
@TestConfiguration(proxyBeanMethods=false)
public class PersistentQualificationMediaFixtures {
    @Bean @Primary PrivateAttachmentObjectStore persistentSyntheticStore(Environment environment)throws IOException {
        Path root=Path.of(environment.getRequiredProperty("synthetic.qualification.object-directory")).toAbsolutePath().normalize();Files.createDirectories(root);
        return new PrivateAttachmentObjectStore(){
            private void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Object I/O inside transaction");}
            private Path path(String reference) {
                try{return root.resolve(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8))));}
                catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            }
            public InputStream openRange(String reference,long offset,long length) {
                outside();try {var input=Files.newInputStream(path(reference));input.skipNBytes(offset);
                    return new FilterInputStream(input){long remaining=length;
                        @Override public int read()throws IOException{if(remaining<=0)return -1;int value=super.read();if(value>=0)remaining--;return value;}
                        @Override public int read(byte[] b,int start,int size)throws IOException{if(remaining<=0)return -1;int n=super.read(b,start,(int)Math.min(size,remaining));if(n>0)remaining-=n;return n;}};
                }catch(IOException failure){throw new IllegalStateException("Synthetic object unavailable");}
            }
            public void write(String reference,InputStream source,long size) {
                outside();Path path=path(reference);try(var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)) {
                    long remaining=size;byte[] buffer=new byte[8192];while(remaining>0){int read=source.read(buffer,0,(int)Math.min(buffer.length,remaining));if(read<0)throw new IOException("Length mismatch");output.write(buffer,0,read);remaining-=read;}
                    if(source.read()!=-1)throw new IOException("Length mismatch");
                }catch(IOException failure){throw new IllegalStateException("Synthetic object write failed");}
            }
            public void delete(String reference){outside();try{Files.deleteIfExists(path(reference));}catch(IOException failure){throw new IllegalStateException("Synthetic deletion failed");}}
            // No autonomous cleanup is enabled in this explicit local harness.
            public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return List.of();}
        };
    }
}
