package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("FAST") @Tag("SECURITY")
class OciPrivateAttachmentObjectStoreTest {
    @TempDir Path directory;
    private static String key(int value){return "private-attachment/"+String.format("%064x",value);}
    @Test void realSdkCreatesChecksummedStreamingObjectsWithoutOverwriteAndSurvivesRestart()throws Exception {
        byte[] bytes=new byte[600_000];new java.util.Random(17).nextBytes(bytes);
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            store.write(key(1),new ByteArrayInputStream(bytes),bytes.length);
            assertThat(transport.createOnly).isTrue();assertThat(transport.checksumVerified).isTrue();assertThat(transport.largestRead).isLessThanOrEqualTo(8192);
            assertThatThrownBy(()->store.write(key(1),new ByteArrayInputStream(new byte[]{9}),1)).isInstanceOf(ApiFailureException.class);
            assertThat(Files.readAllBytes(directory.resolve(key(1).substring(19)))).isEqualTo(bytes);
        }
        try(var restarted=ControlledObjectStorageTransport.store(new ControlledObjectStorageTransport(directory));var input=restarted.openRange(key(1),31,1000)){
            assertThat(input.readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(bytes,31,1031));
        }
        assertThat(transport.calls).hasValue(2);
    }
    @Test void lostWriteResponseIsNotRetriedAndLeavesAnIdentifiableOrphan()throws Exception {
        var transport=new ControlledObjectStorageTransport(directory);transport.ambiguousPut=true;
        try(var store=ControlledObjectStorageTransport.store(transport)){
            assertThatThrownBy(()->store.write(key(2),new ByteArrayInputStream(new byte[]{1,2,3}),3))
                    .isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(1);assertThat(objectCount()).isEqualTo(1);
            store.delete(key(2));store.delete(key(2));assertThat(objectCount()).isZero();
        }
    }
    @Test void incompleteOrOversizeInputFailsBeforeRemoteWrite(){
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            for(long size:new long[]{0,2,20_000_001})assertThatThrownBy(()->store.write(key(1),new ByteArrayInputStream(new byte[]{1}),size)).isInstanceOf(ApiFailureException.class);
            assertThatThrownBy(()->store.write(key(1),new ByteArrayInputStream(new byte[]{1,2}),1)).isInstanceOf(ApiFailureException.class);
            assertThat(transport.calls).hasValue(0);
        }
    }
    @Test void rangeFramingAndPrematureEofAreStrictAndStreamsReleaseAdmission()throws Exception {
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            store.write(key(1),new ByteArrayInputStream(new byte[]{1,2,3,4,5}),5);
            transport.wrongRange=true;
            assertThatThrownBy(()->store.openRange(key(1),0,3)).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.closedBody).isTrue();transport.wrongRange=false;transport.shortRange=true;
            try(var input=store.openRange(key(1),0,3)){assertThatThrownBy(input::readAllBytes).isInstanceOf(IOException.class).hasMessage("Stored bytes unavailable").hasNoCause();}
            transport.shortRange=false;
            for(int i=0;i<8;i++)try(var input=store.openRange(key(1),1,3)){assertThat(input.skip(1)).isEqualTo(1);assertThat(input.readAllBytes()).containsExactly(3,4);}
            var streams=new java.util.ArrayList<InputStream>();
            try{for(int i=0;i<4;i++)streams.add(store.openRange(key(1),0,1));assertThatThrownBy(()->store.openRange(key(1),0,1)).isInstanceOf(ApiFailureException.class);}
            finally{for(var input:streams)input.close();}
            try(var input=store.openRange(key(1),0,1)){assertThat(input.read()).isEqualTo(1);}
        }
    }
    @Test void inventoryAdvancesPastRecentObjectsAndDeletionFailureIsNotSuccess()throws Exception {
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            for(int i=1;i<=3;i++)store.write(key(i),new ByteArrayInputStream(new byte[]{1}),1);
            var cutoff=Instant.now().minusSeconds(3600);Files.setLastModifiedTime(directory.resolve(key(3).substring(19)),java.nio.file.attribute.FileTime.from(cutoff.minusSeconds(1)));
            var first=store.scanBefore(cutoff,null,2);assertThat(first.objects()).isEmpty();assertThat(first.continueAfter()).isEqualTo(key(2));
            var second=store.scanBefore(cutoff,first.continueAfter(),2);assertThat(second.objects()).extracting(PrivateAttachmentObjectStore.StoredObject::reference).containsExactly(key(3));assertThat(second.continueAfter()).isNull();
            transport.failDelete=true;int before=transport.calls.get();
            assertThatThrownBy(()->store.delete(key(3))).isInstanceOf(ApiFailureException.class);assertThat(transport.calls.get()-before).isEqualTo(1);
            assertThat(Files.exists(directory.resolve(key(3).substring(19)))).isTrue();
        }
    }
    @Test void historicallyAcceptedVideoStillSupportsRangesBeyondNewUploadLimit()throws Exception {
        try(var file=new java.io.RandomAccessFile(directory.resolve(key(1).substring(19)).toFile(),"rw")){
            file.setLength(50L*1024*1024);file.seek(25L*1024*1024);file.write(new byte[]{3,4,5});
        }
        try(var store=ControlledObjectStorageTransport.store(new ControlledObjectStorageTransport(directory));
                var input=store.openRange(key(1),25L*1024*1024,3)){
            assertThat(input.readAllBytes()).containsExactly(3,4,5);
        }
    }
    @Test void ignoredRangesMissingObjectsAndMalformedInventoryFailClosed(){
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            assertThatThrownBy(()->store.openRange(key(1),0,1)).isInstanceOf(ApiFailureException.class).hasNoCause();
            store.write(key(1),new ByteArrayInputStream(new byte[]{1,2,3}),3);transport.ignoredRange=true;
            assertThatThrownBy(()->store.openRange(key(1),0,2)).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.closedBody).isTrue();transport.malformedInventory=true;
            assertThatThrownBy(()->store.scanBefore(Instant.now(),null,5)).isInstanceOf(ApiFailureException.class).hasNoCause();
            int calls=transport.calls.get();
            for(int invalid:new int[]{0,101})assertThatThrownBy(()->store.scanBefore(Instant.now(),null,invalid)).isInstanceOf(ApiFailureException.class);
            assertThat(transport.calls.get()).isEqualTo(calls);
        }
    }
    @Test void locatorsTransactionsAndRedirectsCannotExpandAuthority(){
        var transport=new ControlledObjectStorageTransport(directory);
        try(var store=ControlledObjectStorageTransport.store(transport)){
            for(String invalid:new String[]{"../private","public-media/"+"a".repeat(64),"private-attachment/short"})
                assertThatThrownBy(()->store.delete(invalid)).isInstanceOf(ApiFailureException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{assertThatThrownBy(()->store.delete(key(1))).isInstanceOf(ApiFailureException.class);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
            assertThat(transport.calls).hasValue(0);transport.redirect=true;
            assertThatThrownBy(()->store.write(key(1),new ByteArrayInputStream(new byte[]{1}),1)).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(1);assertThat(store.toString()).doesNotContain("synthetic");
        }
    }
    private long objectCount()throws IOException{try(var files=Files.list(directory)){return files.count();}}
}
