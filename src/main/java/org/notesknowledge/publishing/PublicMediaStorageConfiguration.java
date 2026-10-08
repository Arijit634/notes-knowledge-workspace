package org.notesknowledge.publishing;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
class PublicMediaStorageConfiguration {
    @Bean @ConditionalOnMissingBean(PublicMediaObjectStore.class)
    PublicMediaObjectStore unavailablePublicMediaStore(){return new PublicMediaObjectStore(){
        private RuntimeException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
        public void write(String ref,InputStream source,long size){throw unavailable();}
        public InputStream openRange(String ref,long offset,long length){throw unavailable();}
        public void delete(String ref){throw unavailable();}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){throw unavailable();}
    };}
}
