package org.notesknowledge.profile;

import java.time.Instant;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AvatarStorageConfiguration {
    @Bean @ConditionalOnMissingBean(AvatarObjectStore.class)
    AvatarObjectStore unavailableAvatarObjectStore() {
        return new AvatarObjectStore() {
            public void write(String reference, byte[] bytes) { throw unavailable(); }
            public void delete(String reference) { throw unavailable(); }
            public List<StoredObject> inventoryBefore(Instant cutoff, String afterReference, int limit) { throw unavailable(); }
        };
    }

    private static ApiFailureException unavailable() {
        return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
}
