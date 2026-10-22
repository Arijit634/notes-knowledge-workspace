package org.notesknowledge;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.notesknowledge.identity.BrowserModeratorFixture;
import org.notesknowledge.identity.BrowserMailboxFixture;
import org.notesknowledge.notes.ConfiguredMediaStorageFixtures;
import org.notesknowledge.notes.MeaningfulMediaFixtures;
import org.notesknowledge.profile.ConfiguredAvatarStorageFixtures;
import org.notesknowledge.publishing.ConfiguredPublicMediaStorageFixtures;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Opt-in browser qualification entry point. Never included in the application JAR.
 * Controllers, sessions, SQL, validation and S3 adapters are real. Only external
 * mail/object transports are controlled. No authentication fixture endpoint exists.
 */
public final class BrowserIntegrationServer {
    private BrowserIntegrationServer() { }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({BrowserMailboxFixture.class, ConfiguredMediaStorageFixtures.class,
        ConfiguredAvatarStorageFixtures.class, ConfiguredPublicMediaStorageFixtures.class})
    public static class ExternalBoundaries { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit disposable runtime directory required");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("commands"));
        Files.createDirectories(root.resolve("responses"));
        for (String name : java.util.List.of("media", "avatars", "public-media", "fixtures"))
            Files.createDirectory(root.resolve(name));
        var json = new ObjectMapper();
        try (var postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
                .withDatabaseName("browser_integration").withUsername("synthetic_migrator")
                .withPassword("synthetic-browser-migration-password").withLabel("org.notesknowledge.browser-run", root.getFileName().toString())
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
             var redis = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379)
                .withLabel("org.notesknowledge.browser-run", root.getFileName().toString())
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(128L * 1024 * 1024).withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(6379))))) {
            postgres.start(); redis.start();
            var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();
            // Identity only: deliberately no displayable consent text or acknowledgement.
            admin.update("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('synthetic_browser',1,?,'synthetic-no-legal-approval',clock_timestamp())", "a".repeat(64));
            admin.execute("create role synthetic_browser_runtime login password 'synthetic-browser-runtime-password' nosuperuser nocreatedb nocreaterole noreplication");
            try (var resource = BrowserIntegrationServer.class.getResourceAsStream("/operations/runtime-role.sql")) {
                if (resource == null) throw new IllegalStateException("Runtime grants unavailable");
                admin.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace(":\"runtime_role\"", "synthetic_browser_runtime"));
            }
            var values = new LinkedHashMap<String, String>();
            values.put("server.address", "127.0.0.1"); values.put("server.port", "18080");
            values.put("spring.datasource.url", postgres.getJdbcUrl());
            values.put("spring.datasource.username", "synthetic_browser_runtime");
            values.put("spring.datasource.password", "synthetic-browser-runtime-password");
            values.put("spring.datasource.hikari.maximum-pool-size", "5");
            values.put("spring.flyway.url", postgres.getJdbcUrl());
            values.put("spring.flyway.user", postgres.getUsername()); values.put("spring.flyway.password", postgres.getPassword());
            values.put("spring.data.redis.host", redis.getHost());
            values.put("spring.data.redis.port", redis.getMappedPort(6379).toString());
            values.put("synthetic.media-store", root.resolve("media").toString());
            values.put("synthetic.avatar-store", root.resolve("avatars").toString());
            values.put("synthetic.publication-store", root.resolve("public-media").toString());
            values.put("identity.delivery.public-origin", "https://localhost:4175");
            values.put("identity.delivery.polling-enabled", "false");
            values.put("identity.oidc.google.enabled", "false");
            values.put("identity.delivery.smtp-enabled", "false");
            values.put("knowledge.derivation.scheduling-enabled", "false");
            values.put("knowledge.derivation.enabled", "false");
            values.put("knowledge.public.scheduling-enabled", "false");
            values.put("knowledge.processing-policy.code", "synthetic_browser");
            // Fresh, in-memory test keys; never written to receipts or command files.
            for (String key : java.util.List.of("identity.delivery.key-base64", "identity.mfa.key-base64",
                    "identity.mfa.handle-key-base64", "identity.session-handle.key-base64", "identity.rate.key-base64", "notes.cursor.active.key-base64",
                    "publishing.preview.key-base64", "knowledge.operations.handle.key-base64", "knowledge.operations.envelope.key-base64")) {
                byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
                values.put(key, Base64.getEncoder().encodeToString(bytes)); Arrays.fill(bytes, (byte) 0);
            }
            values.put("notes.cursor.active.version", "browser1");
            values.put("knowledge.operations.handle.key-version", "browser1");
            values.put("knowledge.operations.envelope.key-version", "browser1");
            var arguments = new ArrayList<String>();
            values.forEach((key, value) -> arguments.add("--" + key + "=" + value));
            // No user .env or ignored external provider configuration is loaded.
            arguments.add("--spring.config.location=classpath:/application.properties");
            var launcher = new SpringApplication(Application.class, ExternalBoundaries.class);
            try (var app = launcher.run(arguments.toArray(String[]::new))) {
                var runtime = app.getBean(JdbcTemplate.class);
                require(Boolean.FALSE.equals(runtime.queryForObject("select rolsuper or rolcreatedb or rolcreaterole from pg_roles where rolname=current_user", Boolean.class)));
                require(runtime.queryForObject("select count(*) from information_schema.tables where table_schema in ('identity','profile','notes','knowledge','publishing','discovery','moderation') and table_type='BASE TABLE'", Integer.class) == 38);
                for (String kind : java.util.List.of("image", "pdf", "audio", "video")) {
                    var media = MeaningfulMediaFixtures.media(kind);
                    Files.write(root.resolve("fixtures").resolve(media.getOriginalFilename()), media.getBytes());
                }
                Files.writeString(root.resolve("ready.json"), json.writeValueAsString(Map.of(
                    "backendOrigin", "http://127.0.0.1:18080", "postgresVersion", runtime.queryForObject("show server_version", String.class),
                    "relations", 38, "runtimeRoleRestricted", true, "externalMailControlled", true,
                    "externalObjectsControlled", true, "realRedis", true, "liveDerivationEnabled",
                    app.getEnvironment().getProperty("knowledge.derivation.enabled", Boolean.class, false))));
                while (!Files.exists(root.resolve("stop"))) {
                    try (var files = Files.list(root.resolve("commands"))) {
                        for (var file : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted().toList()) {
                            Path response = root.resolve("responses").resolve(file.getFileName());
                            if (Files.exists(response)) continue;
                            boolean ok = false;
                            try {
                                if (Files.size(file) > 1024) throw new IllegalArgumentException();
                                var request = json.readTree(Files.readString(file));
                                switch (request.path("action").asString()) {
                                    case "verify-registration" -> verifyRegistration(app, json);
                                    case "grant-moderation" -> BrowserModeratorFixture.grant(admin, postgres.getJdbcUrl(), request.path("email").asString());
                                    case "isolate-transient-rate-state" -> {
                                        // Fixture-only test-case isolation in this dedicated disposable
                                        // Redis container. Never called in response to a rejected request.
                                        // No database/session/privilege state or production policy changes.
                                        try (var connection = app.getBean(org.springframework.data.redis.connection.RedisConnectionFactory.class).getConnection()) {
                                            connection.serverCommands().flushDb();
                                        }
                                    }
                                    default -> throw new IllegalArgumentException();
                                }
                                ok = true;
                            } catch (Exception failure) { /* No capabilities, credentials or request payloads in diagnostics. */ }
                            Files.writeString(response, json.writeValueAsString(Map.of("ok", ok)));
                        }
                    }
                    Thread.sleep(100); // Bounded local fixture-control polling, not a test timing assertion.
                }
            }
        }
        Files.writeString(root.resolve("stopped.json"), json.writeValueAsString(Map.of("applicationClosed", true, "disposableServicesClosed", true)));
    }

    private static void verifyRegistration(org.springframework.context.ApplicationContext app, ObjectMapper json) throws Exception {
        String token = BrowserMailboxFixture.verificationToken(app);
        Path certificate = Path.of(System.getenv("NKW_INTEGRATION_TLS_DIRECTORY"), "localhost.cer");
        var trust = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null, null);
        try (var input = Files.newInputStream(certificate)) {
            trust.setCertificateEntry("exact-synthetic-localhost", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        URI origin = URI.create("https://localhost:4175");
        var csrf = client.send(HttpRequest.newBuilder(origin.resolve("/api/auth/csrf")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(csrf.statusCode() == 200);
        var result = client.send(HttpRequest.newBuilder(origin.resolve("/api/auth/email-verification/confirmations"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", json.readTree(csrf.body()).path("csrfToken").asString())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("token", token)))).build(), HttpResponse.BodyHandlers.discarding());
        require(result.statusCode() == 204);
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Browser integration fixture invariant failed");
    }
}
