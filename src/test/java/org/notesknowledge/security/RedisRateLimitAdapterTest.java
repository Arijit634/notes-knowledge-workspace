package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;

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
            var adapter = new RedisRateLimitAdapter(template);
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
}
