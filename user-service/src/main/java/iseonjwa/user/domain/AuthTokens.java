package iseonjwa.user.domain;

import java.time.Duration;
import java.time.Instant;

public interface AuthTokens {
    record Pair(String accessToken, String refreshToken, long accessTokenExpiresIn) {}
    record Identity(Long userId, String jti, Instant expiresAt) {}
    Pair issue(Long userId, String role);
    Identity decodeAccess(String token);
    Identity decodeRefresh(String token);
    Duration refreshTtl();
}
