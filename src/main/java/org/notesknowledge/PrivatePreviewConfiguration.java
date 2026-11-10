package org.notesknowledge;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Restricted default; complete hosted selection keeps the same perimeter and explicit provider posture. */
@Configuration(proxyBeanMethods = false)
@Profile({"private-preview","full-hosted"})
public class PrivatePreviewConfiguration {
    @Bean PrivatePreview privatePreview(Environment environment) {
        return validate(environment, System.getProperty("os.name"), System.getProperty("os.arch"));
    }
    PrivatePreview validate(Environment environment, String os, String architecture) {
        try {
            boolean managed=Set.of(environment.getActiveProfiles()).contains("managed-preview");
            boolean full=Set.of(environment.getActiveProfiles()).contains("full-hosted");
            var profiles=full?Set.of("operations","private-preview","managed-preview","full-hosted"):
                managed?Set.of("operations","private-preview","managed-preview"):Set.of("operations","private-preview");
            var origin = URI.create(environment.getRequiredProperty("nkw.preview.origin"));
            if (!"Linux".equals(os) || !Set.of("amd64", "x86_64", "aarch64").contains(architecture)
                    || (full&&!managed) || !profiles.equals(Set.of(environment.getActiveProfiles()))
                    || !"https".equals(origin.getScheme()) || origin.getHost() == null
                    || !origin.getHost().matches("[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}")
                    || origin.getHost().equals("localhost") || origin.getPort() != -1
                    || origin.getRawUserInfo() != null || !origin.getRawPath().isEmpty()
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(managed?"password-gated":"ip-restricted").equals(environment.getProperty("nkw.preview.access"))
                    || !(managed?"file:/etc/secrets/runtime.properties":"configtree:/run/secrets/")
                        .equals(environment.getProperty("spring.config.import"))
                    || !"8080".equals(environment.getProperty("server.port"))
                    || !"".equals(environment.getProperty("server.servlet.context-path", ""))
                    || !(managed?"127.0.0.1":"0.0.0.0").equals(environment.getProperty("server.address"))
                    || !"127.0.0.1".equals(environment.getProperty("management.server.address"))
                    || !"8081".equals(environment.getProperty("management.server.port"))
                    || !"native".equals(environment.getProperty("server.forward-headers-strategy"))
                    || !(managed?"127\\.0\\.0\\.1":"10\\.89\\.10\\.2").equals(environment.getProperty("server.tomcat.remoteip.internal-proxies"))
                    || !(managed?managedDatabaseUrl(environment.getProperty("nkw.preview.database-host")):
                        "jdbc:postgresql://postgres:5432/nkw_preview").equals(environment.getProperty("spring.datasource.url"))
                    || !"nkw_preview_runtime".equals(environment.getProperty("spring.datasource.username"))
                    || !environment.getProperty("identity.oidc.google.enabled", Boolean.class, false)
                    || !"https://accounts.google.com".equals(environment.getProperty("identity.oidc.google.issuer"))
                    || !(origin + "/api/auth/oidc/google/callback").equals(environment.getProperty("identity.oidc.google.login-redirect-uri"))
                    || !(origin + "/api/auth/oidc/google/link-callback").equals(environment.getProperty("identity.oidc.google.link-redirect-uri"))) {
                throw new IllegalStateException();
            }
            if(managed && (!"127.0.0.1".equals(environment.getProperty("spring.data.redis.host"))
                    || !"20s".equals(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")))) {
                throw new IllegalStateException();
            }
            for (String property : List.of("spring.flyway.enabled", "nkw.localhost.synthetic-ai",
                    "server.tomcat.accesslog.enabled", "server.ssl.enabled")) {
                if (environment.getProperty(property, Boolean.class, false)) throw new IllegalStateException();
            }
            for (String property : List.of("identity.delivery.smtp-enabled", "identity.delivery.polling-enabled",
                    "knowledge.derivation.enabled", "knowledge.derivation.scheduling-enabled",
                    "knowledge.public.scheduling-enabled", "knowledge.operations.scheduling-enabled",
                    "notes.media-runtime.pdf.enabled", "notes.media-runtime.video.enabled")) {
                if (environment.getProperty(property, Boolean.class, false)!=full) throw new IllegalStateException();
            }
            if(full) {
                for(var entry:java.util.Map.of("knowledge.derivation.provider","gemini",
                        "knowledge.derivation.tier","unpaid","knowledge.derivation.region","global",
                        "knowledge.derivation.dispatch-policy","unpaid-nonsensitive-consent").entrySet()) {
                    if(!entry.getValue().equals(environment.getProperty(entry.getKey())))throw new IllegalStateException();
                }
                for(String key:List.of("auth","starttls.enable","starttls.required","ssl.checkserveridentity")) {
                    if(!environment.getProperty("spring.mail.properties.mail.smtp."+key,Boolean.class,false))
                        throw new IllegalStateException();
                }
            }
            for (String prefix : List.of("notes.private-storage", "profile.avatar-storage",
                    "profile.public-avatar-storage", "publishing.media-storage")) {
                if (!environment.getProperty(prefix + ".enabled", Boolean.class, false)
                        || !"oci-s3-private-v1".equals(environment.getProperty(prefix + ".profile"))) {
                    throw new IllegalStateException();
                }
            }
            return new PrivatePreview(origin.getHost(),full);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Restricted private preview configuration required");
        }
    }

    static String managedDatabaseUrl(String host) {
        if(host==null || host.length()>253 || !host.matches("ep-[a-z0-9-]+\\.[a-z0-9.-]+\\.neon\\.tech")
                || host.contains("-pooler.") || host.contains(".."))throw new IllegalArgumentException();
        return "jdbc:postgresql://"+host+":5432/nkw_preview?sslmode=verify-full"
            +"&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&connectTimeout=10&socketTimeout=30";
    }

    public record PrivatePreview(String host, boolean fullApplication) {
        public PrivatePreview(String host) { this(host,false); }
    }
}
