package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("SECURITY")
@Testcontainers
class RedisRateLimitAdapterTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:8.10.2")
            .withExposedPorts(6379);

    @Test
    void transientAtomicLimitThrottlesAndDoesNotTreatKeyAsAuthority() {
        var factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var adapter = new RedisRateLimitAdapter(template,
                    new IdentityRateProperties(60, 86400, 6, 6, 12, 10, 6, 12, 1200, 250, 8, 6, 8));
            var request = new RateLimitPort.Request(
                    new RateLimitPort.ControlClass("REGISTRATION"),
                    new RateLimitPort.OpaqueKey("syntheticOpaqueRateKey789"), 1);
            for (int i = 0; i < 6; i++) {
                assertThat(adapter.evaluate(request)).isInstanceOf(RateLimitPort.Allowed.class);
            }
            assertThat(adapter.evaluate(request))
                    .isEqualTo(new RateLimitPort.Throttled(60));
            assertThat(template.getExpire("identity:rate:REGISTRATION:syntheticOpaqueRateKey789"))
                    .isBetween(1L, 60L);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void rotatingBothSpecificDimensionsStillHitsSharedGlobalAndKeysStayOpaque() {
        var factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var adapter = new RedisRateLimitAdapter(template,
                    new IdentityRateProperties(60, 86400, 10, 10, 10, 10, 6, 12, 3, 2, 8, 6, 8));
            var keys = new RateKeyDeriver(
                    "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
            for (int index = 0; index < 4; index++) {
                String candidate = "candidate-" + index + "@example.test";
                String source = "192.0.2." + (index + 1);
                var global = request("IDENTITY_GLOBAL",
                        keys.derive("IDENTITY_GLOBAL", "whole-deployment"));
                var globalDecision = adapter.evaluate(global);
                if (index == 3) {
                    assertThat(globalDecision).isEqualTo(new RateLimitPort.Throttled(60));
                    break;
                }
                assertThat(globalDecision).isInstanceOf(RateLimitPort.Allowed.class);
                assertThat(adapter.evaluate(request("VERIFICATION_REQUEST",
                        keys.derive("VERIFICATION_REQUEST", "source:" + source))))
                        .isInstanceOf(RateLimitPort.Allowed.class);
                assertThat(adapter.evaluate(request("VERIFICATION_REQUEST",
                        keys.derive("VERIFICATION_REQUEST", "candidate:" + candidate))))
                        .isInstanceOf(RateLimitPort.Allowed.class);
            }
            assertThat(adapter.evaluate(request("SECURITY_EMAIL_PROVIDER",
                    keys.derive("SECURITY_EMAIL_PROVIDER", "whole-deployment"))))
                    .isInstanceOf(RateLimitPort.Allowed.class);
            assertThat(adapter.evaluate(request("SECURITY_EMAIL_PROVIDER",
                    keys.derive("SECURITY_EMAIL_PROVIDER", "whole-deployment"))))
                    .isInstanceOf(RateLimitPort.Allowed.class);
            assertThat(adapter.evaluate(request("SECURITY_EMAIL_PROVIDER",
                    keys.derive("SECURITY_EMAIL_PROVIDER", "whole-deployment"))))
                    .isEqualTo(new RateLimitPort.Throttled(86400));
            assertThat(template.keys("identity:rate:*")).allSatisfy(key ->
                    assertThat(key).doesNotContain("@", "192.0.2", "candidate-"));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void providerRetryAfterUsesAtomicRemainingWindowRatherThanFullDay() {
        var factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var adapter = new RedisRateLimitAdapter(template,
                    new IdentityRateProperties(60, 86400, 6, 6, 12, 10, 6, 12, 1200, 1, 8, 6, 8));
            String key = "identity:rate:SECURITY_EMAIL_PROVIDER:syntheticOpaqueTtlKey789";
            var request = request("SECURITY_EMAIL_PROVIDER",
                    new RateLimitPort.OpaqueKey("syntheticOpaqueTtlKey789"));
            assertThat(adapter.evaluate(request)).isInstanceOf(RateLimitPort.Allowed.class);
            assertThat(template.expire(key, Duration.ofSeconds(3))).isTrue();
            var decision = adapter.evaluate(request);
            assertThat(decision).isInstanceOf(RateLimitPort.Throttled.class);
            assertThat(((RateLimitPort.Throttled) decision).retryAfterSeconds())
                    .isBetween(1, 3);
            assertThat(template.getExpire(key)).isBetween(1L, 3L);
        } finally {
            factory.destroy();
        }
    }

    private RateLimitPort.Request request(String control, RateLimitPort.OpaqueKey key) {
        return new RateLimitPort.Request(new RateLimitPort.ControlClass(control), key, 1);
    }
}
