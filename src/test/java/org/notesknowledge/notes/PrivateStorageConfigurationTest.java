package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class PrivateStorageConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(AttachmentConfiguration.class);
    final String[] valid={"notes.private-storage.enabled=true","notes.private-storage.profile=oci-s3-private-v1",
            "notes.private-storage.endpoint=https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
            "notes.private-storage.region=ap-mumbai-1","notes.private-storage.bucket=synthetic-private-bucket",
            "notes.private-storage.credentials-source=injected","notes.private-storage.private-bucket-confirmed=true",
            "notes.private-storage.access-key-id=synthetic-access-key","notes.private-storage.secret-access-key=synthetic-secret-key"};
    @Test void absentAndDisabledStorageFailClosedWithoutBlockingMetadataStartup(){
        context.run(app->{assertThat(app).hasNotFailed().hasSingleBean(PrivateAttachmentObjectStore.class).doesNotHaveBean(OciPrivateAttachmentObjectStore.class);
            assertThatThrownBy(()->app.getBean(PrivateAttachmentObjectStore.class).openRange("private-attachment/"+"a".repeat(64),0,1))
                    .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);});
        context.withPropertyValues("notes.private-storage.enabled=false").run(app->assertThat(app).hasNotFailed());
    }
    @Test void enabledSelectionRequiresExplicitProfileEndpointCredentialsAndPrivateBucket(){
        context.withPropertyValues("notes.private-storage.enabled=true").run(app->assertThat(app).hasFailed());
        for(String invalid:java.util.List.of("profile=auto","credentials-source=default-chain","private-bucket-confirmed=false",
                "access-key-id=","secret-access-key=","region=us-east-1","bucket=../private"))
            context.withPropertyValues(valid).withPropertyValues("notes.private-storage."+invalid).run(app->assertThat(app).hasFailed());
    }
    @Test void arbitraryOrDowngradedEndpointsAndEmbeddedCredentialsAreDenied(){
        for(String endpoint:java.util.List.of("http://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
                "https://untrusted.invalid","https://localhost","https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com/other",
                "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com?secret=synthetic",
                "https://user@syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com"))
            context.withPropertyValues(valid).withPropertyValues("notes.private-storage.endpoint="+endpoint).run(app->assertThat(app).hasFailed());
    }
    @Test void startupErrorsDoNotExposeCredentialOrEndpointValues(){
        context.withPropertyValues(valid).withPropertyValues("notes.private-storage.profile=unapproved")
                .run(app->{assertThat(app).hasFailed();var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure());
                    assertThat(root.getMessage()).isEqualTo("Private storage configuration unavailable");assertThat(root.getCause()).isNull();});
    }
    @Test void validProductionConfigurationSelectsRealAdapterWithoutCloudAccess(){
        context.withPropertyValues(valid).run(app->{assertThat(app).hasNotFailed().hasSingleBean(PrivateAttachmentObjectStore.class)
                .hasSingleBean(OciPrivateAttachmentObjectStore.class);assertThat(app.getBean(PrivateStorageProperties.class).toString()).isEqualTo("PrivateStorageProperties[REDACTED]");});
    }
}
