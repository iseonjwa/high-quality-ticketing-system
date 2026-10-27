package iseonjwa.user.application;

import iseonjwa.common.response.*;
import iseonjwa.user.domain.*;
import iseonjwa.user.application.AuthResult.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthTokens tokens;
    private final AuthSessions sessions;
    public AuthService(UserRepository users, PasswordEncoder passwords, AuthTokens tokens, AuthSessions sessions) {
        this.users = users; this.passwords = passwords; this.tokens = tokens; this.sessions = sessions;
    }

    @Transactional
    public SignupResult signup(String email, String password, String nickname) {
        validatePasswordBytes(password);
        try {
            var user = users.save(new User(normalize(email), passwords.encode(password), nickname, "USER"));
            return new SignupResult(user.getId(), user.getEmail(), user.getNickname());
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
    }

    public AuthTokens.Pair login(String email, String password) {
        validatePasswordBytes(password);
        var user = users.findByEmail(normalize(email))
                .filter(value -> passwords.matches(password, value.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_CREDENTIALS));
        var pair = tokens.issue(user.getId(), user.getRole());
        sessions.save(user.getId(), pair.refreshToken(), tokens.refreshTtl());
        return pair;
    }

    public AuthTokens.Pair refresh(String refreshToken) {
        var claims = tokens.decodeRefresh(refreshToken);
        var user = users.findById(claims.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        var pair = tokens.issue(user.getId(), user.getRole());
        sessions.rotate(user.getId(), refreshToken, pair.refreshToken(), tokens.refreshTtl());
        return pair;
    }

    public void logout(Long userId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        var claims = tokens.decodeAccess(authorization.substring(7));
        if (!userId.equals(claims.userId())) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        sessions.logout(userId, claims.jti(), Duration.between(Instant.now(), claims.expiresAt()));
    }

    public Me me(Long userId) {
        var user = users.findById(userId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new Me(user.getId(), user.getEmail(), user.getNickname(), user.getRole());
    }

    @Transactional
    public void seedAdmin(String email, String password) {
        var existing = users.findByEmail(normalize(email));
        if (existing.isPresent()) {
            if (!"ADMIN".equals(existing.get().getRole())) throw new IllegalStateException("운영자 이메일에 일반 계정이 있습니다");
            return;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("운영자 비밀번호는 8~72바이트여야 합니다");
        users.save(new User(normalize(email), passwords.encode(password), "운영자", "ADMIN"));
    }

    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
