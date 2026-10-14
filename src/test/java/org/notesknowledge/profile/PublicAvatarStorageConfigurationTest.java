package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class PublicAvatarStorageConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(PublicAvatarStorageConfiguration.class);
    final String[] valid={"profile.public-avatar-storage.enabled=true","profile.public-avatar-storage.profile=oci-s3-private-v1",
            "profile.public-avatar-storage.endpoint=https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
            "profile.public-avatar-storage.region=ap-mumbai-1","profile.public-avatar-storage.bucket=synthetic-private-bucket",
            "profile.public-avatar-storage.credentials-source=injected","profile.public-avatar-storage.private-bucket-confirmed=true",
            "profile.public-avatar-storage.access-key-id=synthetic-access-key","profile.public-avatar-storage.secret-access-key=synthetic-secret-key"};
    @Test void absentAndDisabledStorageFailClosedWithoutBlockingMetadataStartup(){
        context.run(app->{assertThat(app).hasNotFailed().hasSingleBean(PublicAvatarObjectStore.class).doesNotHaveBean(OciPublicAvatarObjectStore.class);
            assertThatThrownBy(()->app.getBean(PublicAvatarObjectStore.class).open("public-profile-avatar/"+"a".repeat(64),1))
                    .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);});
        context.withPropertyValues("profile.public-avatar-storage.enabled=false").run(app->assertThat(app).hasNotFailed());
    }
    @Test void enabledSelectionRequiresExplicitProfileEndpointCredentialsAndPrivateBucket(){
        context.withPropertyValues("profile.public-avatar-storage.enabled=true").run(app->assertThat(app).hasFailed());
        for(String invalid:java.util.List.of("profile=auto","credentials-source=default-chain","private-bucket-confirmed=false",
                "access-key-id=","secret-access-key=","region=us-east-1","bucket=../private"))
            context.withPropertyValues(valid).withPropertyValues("profile.public-avatar-storage."+invalid).run(app->assertThat(app).hasFailed());
    }
    @Test void arbitraryOrDowngradedEndpointsAndEmbeddedCredentialsAreDenied(){
        for(String endpoint:java.util.List.of("http://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
                "https://untrusted.invalid","https://localhost","https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com/other",
                "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com?secret=synthetic",
                "https://user@syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com"))
            context.withPropertyValues(valid).withPropertyValues("profile.public-avatar-storage.endpoint="+endpoint).run(app->assertThat(app).hasFailed());
    }
    @Test void startupErrorsDoNotExposeCredentialOrEndpointValues(){
        context.withPropertyValues(valid).withPropertyValues("profile.public-avatar-storage.profile=unapproved")
                .run(app->{assertThat(app).hasFailed();var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure());
                    assertThat(root.getMessage()).isEqualTo("Avatar storage configuration unavailable");assertThat(root.getCause()).isNull();});
    }
    @Test void validProductionConfigurationSelectsRealAdapterWithoutCloudAccess(){
        context.withPropertyValues(valid).run(app->{assertThat(app).hasNotFailed().hasSingleBean(PublicAvatarObjectStore.class)
                .hasSingleBean(OciPublicAvatarObjectStore.class);assertThat(OciAvatarStorageTest.properties().toString()).isEqualTo("AvatarStorageProperties[REDACTED]");});
    }
}
