package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

@Tag("FAST") @Tag("SECURITY")
class PrivatePreviewConfigurationTest {
    private MockEnvironment valid() throws Exception {
        var properties=new Properties();
        try (var input=getClass().getResourceAsStream("/application-private-preview.properties")) { properties.load(input); }
        var e=new MockEnvironment().withProperty("nkw.preview.origin","https://preview.example.test")
            .withProperty("nkw.preview.namespace","syntheticnamespace").withProperty("nkw.preview.region","ap-mumbai-1").withProperty("nkw.preview.bucket","synthetic-preview-bucket");
        e.getPropertySources().addLast(new PropertiesPropertySource("preview",properties));
        e.setActiveProfiles("operations","private-preview");return e;
    }
    @Test void previewRequiresItsOwnRestrictedRuntime() throws Exception {
        assertThat(new PrivatePreviewConfiguration().validate(valid(),"Linux","amd64").host()).isEqualTo("preview.example.test");
        for(String key:java.util.List.of("identity.delivery.smtp-enabled","identity.delivery.polling-enabled",
                "knowledge.derivation.enabled","knowledge.derivation.scheduling-enabled","knowledge.public.scheduling-enabled",
                "notes.media-runtime.pdf.enabled","notes.media-runtime.video.enabled","spring.flyway.enabled","server.ssl.enabled")) {
            var e=valid().withProperty(key,"true");
            assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(e,"Linux","amd64"))
                .hasMessage("Restricted private preview configuration required").hasNoCause();
        }
        for(var entry:java.util.Map.of("management.server.address","0.0.0.0","nkw.preview.origin","http://preview.example.test",
                "spring.datasource.username","nkw_preview_migrator","nkw.preview.access","public",
                "server.tomcat.remoteip.internal-proxies",".*","spring.datasource.url","jdbc:postgresql://127.0.0.1:55448/nkw_oidc_dev").entrySet()) {
            var e=valid().withProperty(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(e,"Linux","amd64")).isInstanceOf(IllegalStateException.class);
        }
        var e=valid();e.setActiveProfiles("operations","private-preview","localhost");
        assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(e,"Linux","amd64")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(valid(),"Linux","arm")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(valid(),"Windows 11","amd64")).isInstanceOf(IllegalStateException.class);
    }
    @Test void normalProfilesDoNotEnableThePreview() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(PrivatePreviewConfiguration.class)
            .run(app->assertThat(app).hasNotFailed().doesNotHaveBean(PrivatePreviewConfiguration.PrivatePreview.class));
    }
}
