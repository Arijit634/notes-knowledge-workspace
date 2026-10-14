package org.notesknowledge.profile;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** Profile-local transport construction; no ambient credentials or endpoint discovery. */
final class AvatarStorageClients {
    private AvatarStorageClients(){}
    static OciAvatarStorage create(Environment environment,String propertyPrefix,String objectPrefix) {
        try {
            var properties=Binder.get(environment).bind(propertyPrefix,AvatarStorageProperties.class).orElseThrow(IllegalStateException::new);
            properties.requireConfigured();
            String access=environment.getProperty(propertyPrefix+".access-key-id");
            String secret=environment.getProperty(propertyPrefix+".secret-access-key");
            if(access==null||secret==null||!access.matches("[!-~]{16,256}")||!secret.matches("[!-~]{16,256}"))
                throw new IllegalStateException();
            var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret));
            return new OciAvatarStorage(OciAvatarStorage.client(properties,credentials,null),properties.bucket(),objectPrefix);
        }catch(RuntimeException invalid){throw new IllegalStateException("Avatar storage configuration unavailable");}
    }
}
