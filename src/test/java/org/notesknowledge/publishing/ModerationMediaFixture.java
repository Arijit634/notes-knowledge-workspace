package org.notesknowledge.publishing;

import java.io.*;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Synthetic public-copy storage only; no private source adapter or provider. */
@TestConfiguration(proxyBeanMethods=false)
public class ModerationMediaFixture {
    @Bean @Primary PublicMediaObjectStore moderationSyntheticPublicMedia(){return new PublicMediaObjectStore(){
        public void write(String reference,InputStream source,long size){throw new UnsupportedOperationException("Read-only synthetic fixture");}
        public InputStream openRange(String reference,long offset,long length){
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new AssertionError("Storage I/O inside transaction");
            return new ByteArrayInputStream(new byte[]{1,2,3,4,5,6},(int)offset,(int)length);
        }
        public void delete(String reference){ }
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){return List.of();}
    };}
}
