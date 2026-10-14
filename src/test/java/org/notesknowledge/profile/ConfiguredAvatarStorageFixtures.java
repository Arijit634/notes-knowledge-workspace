package org.notesknowledge.profile;

import java.nio.file.*;
import java.time.Instant;
import org.notesknowledge.ControlledS3Transport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;

/** Only the external object service is controlled; production SDK/adapters remain real. */
@TestConfiguration(proxyBeanMethods=false)
public class ConfiguredAvatarStorageFixtures {
    @Bean ControlledS3Transport avatarTransport(org.springframework.core.env.Environment environment)throws Exception {
        var root=Path.of(environment.getRequiredProperty("synthetic.avatar-store"));Files.createDirectories(root);
        return new ControlledS3Transport(root);
    }
    @Bean @Primary OciPrivateAvatarObjectStore syntheticPrivateAvatarStore(@org.springframework.beans.factory.annotation.Qualifier("avatarTransport") ControlledS3Transport transport){
        return new OciPrivateAvatarObjectStore(storage(transport,"private-avatar/"));
    }
    @Bean @Primary OciPublicAvatarObjectStore syntheticPublicAvatarStore(@org.springframework.beans.factory.annotation.Qualifier("avatarTransport") ControlledS3Transport transport){
        return new OciPublicAvatarObjectStore(storage(transport,"public-profile-avatar/"));
    }
    private static OciAvatarStorage storage(ControlledS3Transport transport,String prefix){
        var properties=new AvatarStorageProperties(true,"oci-s3-private-v1",
            "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com","ap-mumbai-1",
            "synthetic-private-bucket","injected",true,false);
        var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key"));
        return new OciAvatarStorage(OciAvatarStorage.client(properties,credentials,transport),properties.bucket(),prefix);
    }
    public static void failDeletion(ApplicationContext app,boolean value){app.getBean("avatarTransport",ControlledS3Transport.class).failDelete=value;}
    public static void ambiguousUpload(ApplicationContext app){app.getBean("avatarTransport",ControlledS3Transport.class).ambiguousPut=true;}
    public static void afterWrite(ApplicationContext app,java.util.function.Consumer<String> action){
        app.getBean("avatarTransport",ControlledS3Transport.class).afterPut=action;
    }
    public static void reconcile(ApplicationContext app){
        app.getBean(AvatarService.class).reconcilePendingPage(null,5);
        app.getBean(AvatarService.class).reconcile(null,5);
        app.getBean(PublicAvatarService.class).reconcile(null,5);
    }
    public static long ageObjects(ApplicationContext app)throws Exception {
        var root=Path.of(app.getEnvironment().getRequiredProperty("synthetic.avatar-store"));
        try(var files=Files.walk(root)){
            var objects=files.filter(Files::isRegularFile).toList();
            for(var file:objects)Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(90_000)));
            return objects.size();
        }
    }
    public static byte[] image(String format)throws Exception{return AvatarImages.image(format);}
}
