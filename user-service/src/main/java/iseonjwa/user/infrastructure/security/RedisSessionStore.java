package iseonjwa.user.infrastructure.security;

import iseonjwa.common.response.*;
import iseonjwa.user.application.UserErrorCode;
import iseonjwa.user.domain.AuthSessions;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;

@Component
public class RedisSessionStore implements AuthSessions {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> LOGOUT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], '1', 'PX', ARGV[1])
            return 1
            """, Long.class);

    public RedisSessionStore(StringRedisTemplate redis) { this.redis = redis; }

    public void save(Long userId, String refresh, Duration ttl) {
        try { redis.opsForValue().set(key(userId), hash(refresh), ttl); }
        catch (DataAccessException exception) { throw unavailable(); }
    }

    public void rotate(Long userId, String oldToken, String newToken, Duration ttl) {
        try {
            var result = redis.execute(ROTATE, List.of(key(userId)), hash(oldToken), hash(newToken), Long.toString(ttl.toMillis()));
            if (!Long.valueOf(1).equals(result)) throw new BusinessException(UserErrorCode.TOKEN_REVOKED);
        } catch (DataAccessException exception) { throw unavailable(); }
    }

    public void logout(Long userId, String jti, Duration ttl) {
        try { redis.execute(LOGOUT, List.of(key(userId), "auth:blacklist:" + jti), Long.toString(Math.max(1, ttl.toMillis()))); }
        catch (DataAccessException exception) { throw unavailable(); }
    }

    private String key(Long userId) { return "auth:refresh:" + userId; }
    private BusinessException unavailable() { return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE); }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
