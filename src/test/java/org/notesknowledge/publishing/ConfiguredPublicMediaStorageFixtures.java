package org.notesknowledge.publishing;

import java.nio.file.*;
import java.time.Instant;
import org.notesknowledge.ControlledS3Transport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;

@TestConfiguration(proxyBeanMethods=false)
public class ConfiguredPublicMediaStorageFixtures {
    @Bean ControlledS3Transport publicationTransport(org.springframework.core.env.Environment environment)throws Exception{
        var root=Path.of(environment.getRequiredProperty("synthetic.publication-store"));Files.createDirectories(root);
        return new ControlledS3Transport(root);
    }
    @Bean @Primary OciPublicMediaObjectStore syntheticPublicMediaStore(ControlledS3Transport publicationTransport){
        var properties=new PublicMediaStorageProperties(true,"oci-s3-private-v1",
            "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com","ap-mumbai-1",
            "synthetic-private-bucket","injected",true,false);
        var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key"));
        return new OciPublicMediaObjectStore(OciPublicMediaObjectStore.client(properties,credentials,publicationTransport),properties.bucket());
    }
    public static void failDeletion(ApplicationContext app,boolean value){app.getBean("publicationTransport",ControlledS3Transport.class).failDelete=value;}
    public static void ambiguousUpload(ApplicationContext app){app.getBean("publicationTransport",ControlledS3Transport.class).ambiguousPut=true;}
    public static void reconcile(ApplicationContext app){app.getBean(PublicationService.class).reconcile(null,5);}
    public static long ageObjects(ApplicationContext app)throws Exception{
        var root=Path.of(app.getEnvironment().getRequiredProperty("synthetic.publication-store"));
        try(var files=Files.walk(root)){
            var objects=files.filter(Files::isRegularFile).toList();
            for(var file:objects)Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(90_000)));
            return objects.size();
        }
    }
}
