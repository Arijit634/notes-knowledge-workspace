package org.notesknowledge.security;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Transient fixed-window enforcement; unavailable Redis fails closed for security-critical callers. */
@Component
final class RedisRateLimitAdapter implements RateLimitPort {
    private static final String LUA = """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current == tonumber(ARGV[1]) then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return current
            """;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

    RedisRateLimitAdapter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Decision evaluate(Request request) {
        // The bucket is server-owned, bounded, and never includes raw email, IP, token, or path.
        int ceiling = switch (request.controlClass().value()) {
            case "REGISTRATION", "VERIFICATION_REQUEST" -> 6;
            case "VERIFICATION_CONFIRMATION" -> 12;
            case "PASSWORD_LOGIN" -> 10;
            default -> 10;
        };
        String key = "identity:rate:" + request.controlClass().value() + ":"
                + request.enforcementKey().value();
        try {
            Long current = redis.execute(script, List.of(key),
                    Integer.toString(request.cost()), "60");
            if (current == null) {
                return new ControlUnavailable();
            }
            return current <= ceiling ? new Allowed() : new Throttled(60);
        } catch (RuntimeException exception) {
            return new ControlUnavailable();
        }
    }
}
