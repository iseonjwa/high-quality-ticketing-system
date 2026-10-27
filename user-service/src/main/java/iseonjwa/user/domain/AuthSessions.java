package iseonjwa.user.domain;

import java.time.Duration;

public interface AuthSessions {
    void save(Long userId, String refresh, Duration ttl);
    void rotate(Long userId, String oldToken, String newToken, Duration ttl);
    void logout(Long userId, String jti, Duration ttl);
}
