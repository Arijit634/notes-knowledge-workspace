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
                "knowledge.derivation.enabled","knowledge.derivation.scheduling-enabled","knowledge.public.scheduling-enabled","knowledge.operations.scheduling-enabled",
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
    private MockEnvironment managed() throws Exception {
        var e=valid();
        var properties=new Properties();
        try(var input=getClass().getResourceAsStream("/application-managed-preview.properties")){properties.load(input);}
        e.getPropertySources().addBefore("preview",new PropertiesPropertySource("managed",properties));
        e.setActiveProfiles("operations","private-preview","managed-preview");
        return e.withProperty("nkw.preview.database-host","ep-synthetic.ap-southeast-1.aws.neon.tech");
    }
    @Test void managedPreviewRequiresVerifiedDatabaseAndLoopbackPerimeter() throws Exception {
        assertThat(new PrivatePreviewConfiguration().validate(managed(),"Linux","amd64").host()).isEqualTo("preview.example.test");
        for(var entry:java.util.Map.of("server.address","0.0.0.0","spring.data.redis.host","redis",
                "server.tomcat.remoteip.internal-proxies",".*","nkw.preview.access","public",
                "spring.config.import","optional:file:/unrelated/runtime.properties",
                "spring.lifecycle.timeout-per-shutdown-phase","90s",
                "spring.datasource.url","jdbc:postgresql://ep-synthetic.ap-southeast-1.aws.neon.tech:5432/nkw_preview?sslmode=require").entrySet()) {
            var e=managed().withProperty(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(e,"Linux","amd64"))
                .hasMessage("Restricted private preview configuration required").hasNoCause();
        }
        var wrongProfiles=managed();wrongProfiles.setActiveProfiles("operations","managed-preview");
        assertThatThrownBy(()->new PrivatePreviewConfiguration().validate(wrongProfiles,"Linux","amd64"))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void managedDatabaseCannotDisableTlsOrUseAnUnrelatedOrPooledHost() {
        for(var host:java.util.List.of("localhost","127.0.0.1","ep-synthetic.aws.neon.tech.evil.test",
                "ep-synthetic-pooler.ap-southeast-1.aws.neon.tech","ep-synthetic..aws.neon.tech",
                "ep-synthetic.aws.neon.tech:5432/other?sslmode=disable","ep-synthetic.aws.neon.tech\n")) {
            assertThatThrownBy(()->PrivatePreviewConfiguration.managedDatabaseUrl(host)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->PrivatePreviewConfiguration.managedDatabaseUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
