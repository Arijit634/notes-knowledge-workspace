package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;
import software.amazon.awssdk.auth.credentials.*;

@Tag("FAST") @Tag("SECURITY")
class OciInteroperabilityQualificationTest {
    @TempDir Path directory;
    private final OciInteroperabilityQualification.Configuration configuration=new OciInteroperabilityQualification.Configuration(
        "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com","ap-mumbai-1","synthetic-private-bucket");
    private final StaticCredentialsProvider credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key"));
    @Test void optInScenarioUsesAllActualAdaptersAndLeavesNoSyntheticObjects()throws Exception {
        var transport=new ControlledS3Transport(directory);
        var evidence=OciInteroperabilityQualification.exercise(OciInteroperabilityQualification.factories(configuration,credentials,transport));
        assertThat(evidence).hasSize(4);
        for(var value:evidence.values())assertThat(((Map<?,?>)value).get("completed")).isEqualTo(true);
        try(var files=Files.walk(directory)){assertThat(files.filter(Files::isRegularFile).count()).isZero();}
        assertThat(transport.createOnly).isTrue();assertThat(transport.checksumVerified).isTrue();
        assertThat(transport.largestRead).isLessThanOrEqualTo(8192);
    }
    @Test void liveConsentIsDenyByDefaultAndEachIndependentConfirmationIsRequired(){
        var allowed=new HashMap<String,String>();
        var keys=List.of("NKW_OCI_QUALIFICATION_APPROVED","NKW_OCI_DEDICATED_TEST_BUCKET_CONFIRMED","NKW_OCI_NO_SPEND_CONFIRMED");
        keys.forEach(key->allowed.put(key,"true"));
        for(String key:keys){var missing=new HashMap<>(allowed);missing.remove(key);
            assertThatThrownBy(()->OciInteroperabilityQualification.requireConsent(missing)).hasMessage("Live OCI qualification not authorized");}
        OciInteroperabilityQualification.requireConsent(allowed);
    }
    @Test void independentSignatureCheckRejectsTamperingWithoutStoringCanonicalRequestOrCredentials(){
        var transport=new ControlledS3Transport(directory);transport.tamperSignedPayload=true;
        for(var entry:OciInteroperabilityQualification.factories(configuration,credentials,transport).entrySet())try(var store=entry.getValue().open()){
            assertThatThrownBy(()->store.write(entry.getKey()+"a".repeat(64),new byte[]{1,2,3}))
                .isInstanceOf(AssertionError.class).hasMessage("Synthetic S3 signature mismatch").hasNoCause();
        }
    }
    @Test void documentedDedicatedEndpointWorksForAllActualAdaptersAndSpoofedHostsFailBeforeIo()throws Exception {
        var c=new OciInteroperabilityQualification.Configuration("https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com",configuration.region(),configuration.bucket());
        var transport=new ControlledS3Transport(directory);transport.customerDomain=true;
        assertThat(OciInteroperabilityQualification.exercise(OciInteroperabilityQualification.factories(c,credentials,transport))).hasSize(4);
        int before=transport.calls.get();
        for(String endpoint:List.of("https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com.untrusted.invalid",
                "https://syntheticnamespace.compat.objectstorage.us-east-1.oci.customer-oci.com",
                "http://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com",
                "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com/other",
                "https://user@syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com")){
            var invalid=new OciInteroperabilityQualification.Configuration(endpoint,c.region(),c.bucket());
            for(var factory:OciInteroperabilityQualification.factories(invalid,credentials,transport).values())
                assertThatThrownBy(factory::open).isInstanceOf(IllegalStateException.class);
        }
        assertThat(transport.calls).hasValue(before);
    }
    @Test void explicitRestartCleanupUsesForcedExactReservationsNotAnInventorySweep()throws Exception {
        Path journal=directory.resolve("journal"),objects=directory.resolve("objects");Files.createDirectories(journal);Files.createDirectories(objects);
        var transport=new ControlledS3Transport(objects);var factories=OciInteroperabilityQualification.factories(configuration,credentials,transport);
        String key="private-attachment/"+"a".repeat(64),unrelated="private-attachment/"+"b".repeat(64);
        OciInteroperabilityQualification.reserve(journal,configuration,"private-attachment/",key);
        try(var store=factories.get("private-attachment/").open()){store.write(key,new byte[]{1});store.write(unrelated,new byte[]{9});}
        // A new process sees only forced reservations; no PUT is replayed.
        OciInteroperabilityQualification.cleanup(journal,configuration,factories);
        assertThat(Files.exists(objects.resolve(key))).isFalse();assertThat(Files.readAllBytes(objects.resolve(unrelated))).containsExactly(9);
        assertThat(transport.calls).hasValue(3);
        var wrong=new OciInteroperabilityQualification.Configuration(configuration.endpoint(),configuration.region(),"other-test-bucket");
        assertThatThrownBy(()->OciInteroperabilityQualification.cleanup(journal,wrong,factories)).isInstanceOf(IllegalStateException.class);
        assertThat(transport.calls).hasValue(3);
    }
    @ParameterizedTest @ValueSource(strings={"private-attachment/","private-avatar/","public-profile-avatar/","public-publication-media/"})
    void iamDenialDoesNotRetryOrRetainProviderExceptions(String prefix){
        var transport=new ControlledS3Transport(directory);transport.denyAccess=true;
        var factory=OciInteroperabilityQualification.factories(configuration,credentials,transport).get(prefix);
        try(var store=factory.open()){
            assertThatThrownBy(()->store.write(prefix+"a".repeat(64),new byte[]{1,2,3})).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(1);
            assertThatThrownBy(()->store.read(prefix+"a".repeat(64),0,3)).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(2);
            assertThatThrownBy(()->store.delete(prefix+"a".repeat(64))).isInstanceOf(ApiFailureException.class).hasNoCause();
            assertThat(transport.calls).hasValue(3);
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-attachment/","private-avatar/","public-profile-avatar/","public-publication-media/"})
    void rotatingInjectedCredentialsAffectsNextSignedRequestWithoutRestart(String prefix)throws Exception {
        var current=new AtomicReference<>(AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key"));
        var transport=new ControlledS3Transport(directory);
        var factory=OciInteroperabilityQualification.factories(configuration,current::get,transport).get(prefix);
        String key=prefix+"a".repeat(64);
        try(var store=factory.open()){
            store.write(key,new byte[]{1,2,3});assertThat(transport.rotatedCredentialRequests).isZero();
            current.set(AwsBasicCredentials.create("synthetic-rotated","synthetic-rotated-secret"));
            try(var input=store.read(key,0,3)){assertThat(input.readAllBytes()).containsExactly(1,2,3);}
            store.delete(key);assertThat(transport.rotatedCredentialRequests).isEqualTo(2);
        }
    }
    @ParameterizedTest @ValueSource(strings={"private-attachment/","private-avatar/","public-profile-avatar/","public-publication-media/"})
    void ambiguousWriteIsNotRepeatedAndQualificationCleansOnlyItsOwnLocator(String prefix)throws Exception {
        var transport=new ControlledS3Transport(directory);transport.ambiguousPut=true;
        var factory=OciInteroperabilityQualification.factories(configuration,credentials,transport).get(prefix);
        Path unrelated=directory.resolve(prefix+"b".repeat(64));Files.createDirectories(unrelated.getParent());Files.write(unrelated,new byte[]{9});
        assertThatThrownBy(()->OciInteroperabilityQualification.exercise(Map.of(prefix,factory))).isInstanceOf(ApiFailureException.class).hasNoCause();
        assertThat(transport.calls).hasValue(3); // One uncertain PUT, two exact DELETE acknowledgements.
        assertThat(Files.readAllBytes(unrelated)).containsExactly(9);
        try(var files=Files.walk(directory)){assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);}
    }
}
