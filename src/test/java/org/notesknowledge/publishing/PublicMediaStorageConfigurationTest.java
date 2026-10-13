package org.notesknowledge.publishing;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class PublicMediaStorageConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(PublicMediaStorageConfiguration.class);
    final String[] valid={"publishing.media-storage.enabled=true","publishing.media-storage.profile=oci-s3-private-v1",
            "publishing.media-storage.endpoint=https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
            "publishing.media-storage.region=ap-mumbai-1","publishing.media-storage.bucket=synthetic-private-bucket",
            "publishing.media-storage.credentials-source=injected","publishing.media-storage.private-bucket-confirmed=true",
            "publishing.media-storage.access-key-id=synthetic-access-key","publishing.media-storage.secret-access-key=synthetic-secret-key"};
    @Test void absentAndDisabledStorageFailClosedWithoutBlockingMetadataStartup(){
        context.run(app->{assertThat(app).hasNotFailed().hasSingleBean(PublicMediaObjectStore.class).doesNotHaveBean(OciPublicMediaObjectStore.class);
            assertThatThrownBy(()->app.getBean(PublicMediaObjectStore.class).openRange("public-publication-media/"+"a".repeat(64),0,1))
                    .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);});
        context.withPropertyValues("publishing.media-storage.enabled=false").run(app->assertThat(app).hasNotFailed());
    }
    @Test void enabledSelectionRequiresExplicitProfileEndpointCredentialsAndPrivateBucket(){
        context.withPropertyValues("publishing.media-storage.enabled=true").run(app->assertThat(app).hasFailed());
        for(String invalid:java.util.List.of("profile=auto","credentials-source=default-chain","private-bucket-confirmed=false",
                "access-key-id=","secret-access-key=","region=us-east-1","bucket=../private"))
            context.withPropertyValues(valid).withPropertyValues("publishing.media-storage."+invalid).run(app->assertThat(app).hasFailed());
    }
    @Test void arbitraryOrDowngradedEndpointsAndEmbeddedCredentialsAreDenied(){
        for(String endpoint:java.util.List.of("http://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
                "https://untrusted.invalid","https://localhost","https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com/other",
                "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com?secret=synthetic",
                "https://user@syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com"))
            context.withPropertyValues(valid).withPropertyValues("publishing.media-storage.endpoint="+endpoint).run(app->assertThat(app).hasFailed());
    }
    @Test void startupErrorsDoNotExposeCredentialOrEndpointValues(){
        context.withPropertyValues(valid).withPropertyValues("publishing.media-storage.profile=unapproved")
                .run(app->{assertThat(app).hasFailed();var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure());
                    assertThat(root.getMessage()).isEqualTo("Public media storage configuration unavailable");assertThat(root.getCause()).isNull();});
    }
    @Test void validProductionConfigurationSelectsRealAdapterWithoutCloudAccess(){
        context.withPropertyValues(valid).run(app->{assertThat(app).hasNotFailed().hasSingleBean(PublicMediaObjectStore.class)
                .hasSingleBean(OciPublicMediaObjectStore.class);assertThat(app.getBean(PublicMediaStorageProperties.class).toString()).isEqualTo("PublicMediaStorageProperties[REDACTED]");});
    }
}
