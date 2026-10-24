package org.notesknowledge.publishing;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(PublicMediaStorageProperties.class)
class PublicMediaStorageConfiguration {
    @Bean @ConditionalOnMissingBean(PublicMediaObjectStore.class)
    @org.springframework.context.annotation.Profile("!localhost")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="publishing.media-storage",name="enabled",havingValue="false",matchIfMissing=true)
    PublicMediaObjectStore unavailablePublicMediaStore(){return new PublicMediaObjectStore(){
        private RuntimeException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
        public void write(String ref,InputStream source,long size){throw unavailable();}
        public InputStream openRange(String ref,long offset,long length){throw unavailable();}
        public void delete(String ref){throw unavailable();}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){throw unavailable();}
    };}
    @Bean(destroyMethod="close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="publishing.media-storage",name="enabled",havingValue="true")
    OciPublicMediaObjectStore publicMediaObjectStore(PublicMediaStorageProperties properties,org.springframework.core.env.Environment environment) {
        try {
            properties.requireConfigured();
            String access=environment.getProperty("publishing.media-storage.access-key-id");
            String secret=environment.getProperty("publishing.media-storage.secret-access-key");
            if(access==null||secret==null||!access.matches("[!-~]{16,256}")||!secret.matches("[!-~]{16,256}"))throw new IllegalStateException();
            var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(access,secret));
            return new OciPublicMediaObjectStore(OciPublicMediaObjectStore.client(properties,credentials,null),properties.bucket());
        }catch(RuntimeException invalid){throw new IllegalStateException("Public media storage configuration unavailable");}
    }
}
