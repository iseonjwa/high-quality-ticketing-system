package iseonjwa.user.domain;

import java.time.Duration;

public interface AuthSessions {
    void save(Long userId, String refresh, Duration ttl);

    void validateRefresh(Long userId, String refresh);

    void rotate(Long userId, String oldToken, String newToken, Duration ttl);

    void logout(Long userId, String jti, Duration ttl);

    // 비밀번호가 바뀐 사용자의 Refresh를 폐기한다. 이미 발급한 Access는 만료까지 유효하다.
    void revokeRefresh(Long userId);
}
