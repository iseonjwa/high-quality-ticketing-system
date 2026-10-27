package iseonjwa.user.infrastructure.security;

import iseonjwa.common.response.BusinessException;
import iseonjwa.common.response.CommonErrorCode;
import iseonjwa.user.application.UserErrorCode;
import iseonjwa.user.domain.AuthSessions;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

@Component
public class RedisSessionStore implements AuthSessions {
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    // 저장된 해시와 같을 때만 새 해시로 교체한다. 동시에 재사용하면 하나만 성공한다.
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

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(Long userId, String refresh, Duration ttl) {
        try {
            redis.opsForValue().set(refreshKey(userId), hash(refresh), ttl);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void validateRefresh(Long userId, String refresh) {
        try {
            if (!hash(refresh).equals(redis.opsForValue().get(refreshKey(userId)))) {
                throw new BusinessException(UserErrorCode.TOKEN_REVOKED);
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void rotate(Long userId, String oldToken, String newToken, Duration ttl) {
        try {
            var result = redis.execute(ROTATE, List.of(refreshKey(userId)),
                    hash(oldToken), hash(newToken), Long.toString(ttl.toMillis()));
            if (!Long.valueOf(1).equals(result)) {
                throw new BusinessException(UserErrorCode.TOKEN_REVOKED);
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void logout(Long userId, String jti, Duration ttl) {
        try {
            var blacklistMillis = Long.toString(Math.max(1, ttl.toMillis()));
            redis.execute(LOGOUT, List.of(refreshKey(userId), BLACKLIST_PREFIX + jti), blacklistMillis);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public void revokeRefresh(Long userId) {
        try {
            redis.delete(refreshKey(userId));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private String refreshKey(Long userId) {
        return REFRESH_PREFIX + userId;
    }

    private BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
