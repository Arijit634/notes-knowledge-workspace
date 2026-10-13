package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class PrivateAvatarStorageConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(AvatarStorageConfiguration.class);
    final String[] valid={"profile.avatar-storage.enabled=true","profile.avatar-storage.profile=oci-s3-private-v1",
            "profile.avatar-storage.endpoint=https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
            "profile.avatar-storage.region=ap-mumbai-1","profile.avatar-storage.bucket=synthetic-private-bucket",
            "profile.avatar-storage.credentials-source=injected","profile.avatar-storage.private-bucket-confirmed=true",
            "profile.avatar-storage.access-key-id=synthetic-access-key","profile.avatar-storage.secret-access-key=synthetic-secret-key"};
    @Test void absentAndDisabledStorageFailClosedWithoutBlockingMetadataStartup(){
        context.run(app->{assertThat(app).hasNotFailed().hasSingleBean(AvatarObjectStore.class).doesNotHaveBean(OciPrivateAvatarObjectStore.class);
            assertThatThrownBy(()->app.getBean(AvatarObjectStore.class).open("private-avatar/"+"a".repeat(64),1))
                    .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);});
        context.withPropertyValues("profile.avatar-storage.enabled=false").run(app->assertThat(app).hasNotFailed());
    }
    @Test void enabledSelectionRequiresExplicitProfileEndpointCredentialsAndPrivateBucket(){
        context.withPropertyValues("profile.avatar-storage.enabled=true").run(app->assertThat(app).hasFailed());
        for(String invalid:java.util.List.of("profile=auto","credentials-source=default-chain","private-bucket-confirmed=false",
                "access-key-id=","secret-access-key=","region=us-east-1","bucket=../private"))
            context.withPropertyValues(valid).withPropertyValues("profile.avatar-storage."+invalid).run(app->assertThat(app).hasFailed());
    }
    @Test void arbitraryOrDowngradedEndpointsAndEmbeddedCredentialsAreDenied(){
        for(String endpoint:java.util.List.of("http://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
                "https://untrusted.invalid","https://localhost","https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com/other",
                "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com?secret=synthetic",
                "https://user@syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com"))
            context.withPropertyValues(valid).withPropertyValues("profile.avatar-storage.endpoint="+endpoint).run(app->assertThat(app).hasFailed());
    }
    @Test void startupErrorsDoNotExposeCredentialOrEndpointValues(){
        context.withPropertyValues(valid).withPropertyValues("profile.avatar-storage.profile=unapproved")
                .run(app->{assertThat(app).hasFailed();var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure());
                    assertThat(root.getMessage()).isEqualTo("Avatar storage configuration unavailable");assertThat(root.getCause()).isNull();});
    }
    @Test void validProductionConfigurationSelectsRealAdapterWithoutCloudAccess(){
        context.withPropertyValues(valid).run(app->{assertThat(app).hasNotFailed().hasSingleBean(AvatarObjectStore.class)
                .hasSingleBean(OciPrivateAvatarObjectStore.class);assertThat(OciAvatarStorageTest.properties().toString()).isEqualTo("AvatarStorageProperties[REDACTED]");});
    }
}
