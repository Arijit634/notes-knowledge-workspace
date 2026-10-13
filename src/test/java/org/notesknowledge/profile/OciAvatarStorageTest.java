package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.ControlledS3Transport;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.*;

@Tag("FAST") @Tag("SECURITY")
class OciAvatarStorageTest {
    @TempDir Path directory;
    static AvatarStorageProperties properties(){return new AvatarStorageProperties(true,"oci-s3-private-v1",
        "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com","ap-mumbai-1",
        "synthetic-private-bucket","injected",true,false);}
    static OciAvatarStorage storage(ControlledS3Transport transport,String prefix){
        return new OciAvatarStorage(OciAvatarStorage.client(properties(),StaticCredentialsProvider.create(
            AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key")),transport),properties().bucket(),prefix);
    }
    private static String key(String prefix,int n){return prefix+String.format("%064x",n);}

    @ParameterizedTest @ValueSource(strings={"private-avatar/","public-profile-avatar/"})
    void checksummedCreateOnlyObjectsSurviveTransportRestart(String prefix)throws Exception {
        var bytes=new byte[600_000];new java.util.Random(17).nextBytes(bytes);
        var transport=new ControlledS3Transport(directory);
        try(var store=storage(transport,prefix)){
            store.write(key(prefix,1),bytes);assertThat(transport.createOnly).isTrue();assertThat(transport.checksumVerified).isTrue();
            assertThatThrownBy(()->store.write(key(prefix,1),new byte[]{9})).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(Files.readAllBytes(directory.resolve(key(prefix,1)))).isEqualTo(bytes);
        }
        try(var store=storage(new ControlledS3Transport(directory),prefix);var input=store.open(key(prefix,1),bytes.length)){
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
    }
    @Test void publicCopiesNeverAliasPrivateKeysAndPrivateDeletionPreservesPublicBytes()throws Exception {
        var transport=new ControlledS3Transport(directory);
        try(var privateStore=storage(transport,"private-avatar/");var publicStore=storage(transport,"public-profile-avatar/")){
            byte[] original={1,2,3};privateStore.write(key("private-avatar/",1),original);
            try(var input=privateStore.open(key("private-avatar/",1),3)){publicStore.write(key("public-profile-avatar/",1),input.readAllBytes());}
            privateStore.delete(key("private-avatar/",1));
            try(var input=publicStore.open(key("public-profile-avatar/",1),3)){assertThat(input.readAllBytes()).containsExactly(1,2,3);}
            assertThatThrownBy(()->publicStore.open(key("private-avatar/",1),3)).isInstanceOf(ApiFailureException.class);
            assertThatThrownBy(()->privateStore.delete(key("public-profile-avatar/",1))).isInstanceOf(ApiFailureException.class);
            assertThat(privateStore.open(key("private-avatar/",1),3)).isNull();
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-avatar/","public-profile-avatar/"})
    void ambiguousWritesAreNotRetriedAndDeletionFailureIsNotSuccess(String prefix)throws Exception {
        var transport=new ControlledS3Transport(directory);transport.ambiguousPut=true;
        try(var store=storage(transport,prefix)){
            assertThatThrownBy(()->store.write(key(prefix,1),new byte[]{1,2,3})).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(1);assertThat(Files.exists(directory.resolve(key(prefix,1)))).isTrue();
            transport.failDelete=true;assertThatThrownBy(()->store.delete(key(prefix,1))).isInstanceOf(ApiFailureException.class);
            assertThat(transport.calls).hasValue(2);transport.failDelete=false;
            store.delete(key(prefix,1));store.delete(key(prefix,1));assertThat(Files.exists(directory.resolve(key(prefix,1)))).isFalse();
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-avatar/","public-profile-avatar/"})
    void wrongLengthIgnoredRangeAndTruncatedBodyFailClosedAndReleaseStreams(String prefix)throws Exception {
        var transport=new ControlledS3Transport(directory);
        try(var store=storage(transport,prefix)){
            store.write(key(prefix,1),new byte[]{1,2,3});
            assertThatThrownBy(()->store.open(key(prefix,1),2)).isInstanceOf(ApiFailureException.class);
            transport.wrongRange=true;assertThatThrownBy(()->store.open(key(prefix,1),3)).isInstanceOf(ApiFailureException.class);
            transport.wrongRange=false;transport.ignoredRange=true;
            assertThatThrownBy(()->store.open(key(prefix,1),3)).isInstanceOf(ApiFailureException.class);
            transport.ignoredRange=false;transport.shortRange=true;
            try(var input=store.open(key(prefix,1),3)){assertThatThrownBy(input::readAllBytes).isInstanceOf(IOException.class).hasNoCause();}
            transport.shortRange=false;
            for(int i=0;i<8;i++)try(var input=store.open(key(prefix,1),3)){assertThat(input.readAllBytes()).containsExactly(1,2,3);}
            var streams=new java.util.ArrayList<InputStream>();
            try{for(int i=0;i<4;i++)streams.add(store.open(key(prefix,1),3));
                assertThatThrownBy(()->store.open(key(prefix,1),3)).isInstanceOf(ApiFailureException.class);}
            finally{for(var input:streams)input.close();}
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-avatar/","public-profile-avatar/"})
    void inventoryProgressIncludesRecentScannedKeysWithoutDeletingThem(String prefix)throws Exception {
        var transport=new ControlledS3Transport(directory);
        try(var store=storage(transport,prefix)){
            for(int i=1;i<=3;i++)store.write(key(prefix,i),new byte[]{1});
            var cutoff=Instant.now().minusSeconds(3600);
            Files.setLastModifiedTime(directory.resolve(key(prefix,3)),java.nio.file.attribute.FileTime.from(cutoff.minusSeconds(1)));
            var first=store.scanBefore(cutoff,null,2);assertThat(first.objects()).isEmpty();assertThat(first.continueAfter()).isEqualTo(key(prefix,2));
            var second=store.scanBefore(cutoff,first.continueAfter(),2);
            assertThat(second.objects()).extracting(OciAvatarStorage.StoredObject::reference).containsExactly(key(prefix,3));
            assertThat(second.continueAfter()).isNull();
            transport.malformedInventory=true;
            assertThatThrownBy(()->store.scanBefore(cutoff,null,5)).isInstanceOf(ApiFailureException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-avatar/","public-profile-avatar/"})
    void boundariesAndTransactionGuardPreventRemoteCalls(String prefix){
        var transport=new ControlledS3Transport(directory);
        try(var store=storage(transport,prefix)){
            for(String invalid:new String[]{"../secret",prefix+"short","private-attachment/"+"a".repeat(64)})
                assertThatThrownBy(()->store.delete(invalid)).isInstanceOf(ApiFailureException.class);
            assertThatThrownBy(()->store.write(key(prefix,1),new byte[5*1024*1024+1])).isInstanceOf(ApiFailureException.class);
            assertThatThrownBy(()->store.open(key(prefix,1),0)).isInstanceOf(ApiFailureException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{assertThatThrownBy(()->store.write(key(prefix,1),new byte[]{1})).isInstanceOf(ApiFailureException.class);}
            finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
            assertThat(transport.calls).hasValue(0);transport.redirect=true;
            assertThatThrownBy(()->store.write(key(prefix,1),new byte[]{1})).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(1);
        }
    }
}
