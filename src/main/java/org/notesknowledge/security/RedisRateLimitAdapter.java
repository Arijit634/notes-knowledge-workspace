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
            return tostring(current) .. ':' .. tostring(redis.call('PTTL', KEYS[1]))
            """;
    private final StringRedisTemplate redis;
    private final IdentityRateProperties properties;
    private final NotesRateProperties notes;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>(LUA, String.class);

    RedisRateLimitAdapter(StringRedisTemplate redis, IdentityRateProperties properties, NotesRateProperties notes) {
        this.redis = redis;
        this.properties = properties;
        this.notes = notes;
    }

    @Override
    public Decision evaluate(Request request) {
        // The bucket is server-owned, bounded, and never includes raw email, IP, token, or path.
        String control = request.controlClass().value();
        boolean noteControl = "NOTE_AI_BULK".equals(control) || "NOTE_AI_BULK_GLOBAL".equals(control)
                || "ATTACHMENT_UPLOAD".equals(control) || "ATTACHMENT_UPLOAD_GLOBAL".equals(control);
        int ceiling = noteControl ? notes.ceiling(control) : properties.ceiling(control);
        int window = noteControl ? notes.windowSeconds() : properties.windowSeconds(control);
        String key = (noteControl ? "notes:rate:" : "identity:rate:") + control + ":"
                + request.enforcementKey().value();
        try {
            String result = redis.execute(script, List.of(key),
                    Integer.toString(request.cost()),
                    Integer.toString(window));
            if (result == null) {
                return new ControlUnavailable();
            }
            int separator = result.indexOf(':');
            if (separator < 1 || separator == result.length() - 1) {
                return new ControlUnavailable();
            }
            long current = Long.parseLong(result.substring(0, separator));
            long remainingMillis = Long.parseLong(result.substring(separator + 1));
            if (current < request.cost() || remainingMillis < 0
                    || remainingMillis > (long) window * 1_000) {
                return new ControlUnavailable();
            }
            int retryAfterSeconds = (int) Math.max(1, (remainingMillis + 999) / 1_000);
            return current <= ceiling ? new Allowed()
                    : new Throttled(retryAfterSeconds);
        } catch (RuntimeException exception) {
            return new ControlUnavailable();
        }
    }
}
