package org.notesknowledge.notes;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AttachmentConfiguration {
    @Bean AttachmentMediaValidator attachmentMediaValidator() { return new AttachmentMediaValidator(); }
    @Bean @ConditionalOnMissingBean(PrivateAttachmentObjectStore.class)
    PrivateAttachmentObjectStore unavailableAttachmentObjectStore() {
        return new PrivateAttachmentObjectStore() {
            public void write(String reference, InputStream input, long size) { throw unavailable(); }
            public void delete(String reference) { throw unavailable(); }
            public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) { throw unavailable(); }
            private ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
        };
    }
}
