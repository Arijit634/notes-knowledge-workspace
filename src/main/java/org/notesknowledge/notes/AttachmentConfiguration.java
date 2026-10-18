package org.notesknowledge.notes;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivateStorageProperties.class)
class AttachmentConfiguration {
    @Bean AttachmentMediaValidator attachmentMediaValidator(io.micrometer.core.instrument.MeterRegistry meters) { return new AttachmentMediaValidator(meters); }
    @Bean @ConditionalOnMissingBean(PrivateAttachmentObjectStore.class)
    @ConditionalOnProperty(prefix="notes.private-storage",name="enabled",havingValue="false",matchIfMissing=true)
    PrivateAttachmentObjectStore unavailableAttachmentObjectStore() {
        return new PrivateAttachmentObjectStore() {
            public InputStream openRange(String reference, long offset, long length) { throw unavailable(); }
            public void write(String reference, InputStream input, long size) { throw unavailable(); }
            public void delete(String reference) { throw unavailable(); }
            public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) { throw unavailable(); }
            private ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
        };
    }

    @Bean(destroyMethod="close")
    @ConditionalOnProperty(prefix="notes.private-storage",name="enabled",havingValue="true")
    OciPrivateAttachmentObjectStore ociPrivateAttachmentObjectStore(PrivateStorageProperties properties,Environment environment,org.notesknowledge.PublicStorageTelemetry telemetry) {
        try {
            properties.requireConfigured();
            String access=environment.getProperty("notes.private-storage.access-key-id");
            String secret=environment.getProperty("notes.private-storage.secret-access-key");
            if(!credential(access)||!credential(secret))throw new IllegalStateException();
            var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(access,secret));
            return new OciPrivateAttachmentObjectStore(OciPrivateAttachmentObjectStore.client(properties,credentials,null),properties.bucket(),telemetry);
        }catch(RuntimeException invalid){throw new IllegalStateException("Private storage configuration unavailable");}
    }
    private static boolean credential(String value){return value!=null&&value.matches("[!-~]{16,256}");}
}
