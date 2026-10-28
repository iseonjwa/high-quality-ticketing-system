package com.iseonjwa.user.application;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.user.application.AuthResult.Me;
import com.iseonjwa.user.application.AuthResult.PasswordReset;
import com.iseonjwa.user.application.AuthResult.SignupResult;
import com.iseonjwa.user.domain.AuthSessions;
import com.iseonjwa.user.domain.AuthTokens;
import com.iseonjwa.user.domain.User;
import com.iseonjwa.user.domain.UserErrorCode;
import com.iseonjwa.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MIN_PASSWORD_BYTES = 8;
    private static final int TEMPORARY_PASSWORD_LENGTH = 16;
    // 헷갈리는 문자(0/O, 1/l/I)를 뺀 임시 비밀번호 문자 집합
    private static final String TEMPORARY_PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthTokens tokens;
    private final AuthSessions sessions;
    private final TransactionTemplate transactions;
    private final int maxLoginFailures;
    // 없는 이메일도 BCrypt 비교를 해서 응답 시간으로 계정 존재 여부를 알 수 없게 한다.
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, AuthTokens tokens, AuthSessions sessions,
            PlatformTransactionManager transactionManager,
            @Value("${auth.max-login-failures}") int maxLoginFailures) {
        if (maxLoginFailures <= 0) {
            throw new IllegalArgumentException("로그인 실패 제한은 양수여야 합니다");
        }
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.sessions = sessions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxLoginFailures = maxLoginFailures;
        this.dummyPasswordHash = passwords.encode("dummy-password-for-timing");
    }

    @Transactional
    public SignupResult signup(String email, String password, String nickname) {
        validatePasswordBytes(password);
        try {
            var user = users.save(new User(normalize(email), passwords.encode(password), nickname, Roles.USER));
            return new SignupResult(user.getId(), user.getEmail(), user.getNickname());
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
    }

    public AuthTokens.Pair login(String email, String password) {
        var authenticated = authenticate(email, password);
        return transactions.execute(status -> {
            var user = lockUser(authenticated.getId(), CommonErrorCode.UNAUTHORIZED);
            validateUnchangedPassword(authenticated, user);
            validateTokenIssueAllowed(user);
            if (user.getFailedLoginCount() > 0) {
                users.resetLoginFailures(user.getId());
            }
            var pair = tokens.issue(user.getId(), user.getRole());
            sessions.save(user.getId(), pair.refreshToken(), tokens.refreshTtl());
            return pair;
        });
    }

    // 임시 비밀번호로 로그인할 수 없으므로 토큰 없이 현재 비밀번호로 확인한다.
    public void changePassword(String email, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var authenticated = authenticate(email, currentPassword);
        var newHash = passwords.encode(newPassword);
        transactions.executeWithoutResult(status -> {
            var user = lockUser(authenticated.getId(), CommonErrorCode.UNAUTHORIZED);
            validateUnchangedPassword(authenticated, user);
            validateUnlocked(user);
            sessions.revokeRefresh(user.getId());
            users.updatePassword(user.getId(), newHash, false, user.getId());
        });
    }

    // Redis 폐기를 먼저 해서 실패하면 비밀번호도 바뀌지 않게 한다.
    public PasswordReset resetPassword(Long adminId, Long targetUserId) {
        var temporaryPassword = generateTemporaryPassword();
        var temporaryHash = passwords.encode(temporaryPassword);
        return transactions.execute(status -> {
            var target = lockUser(targetUserId, CommonErrorCode.NOT_FOUND);
            sessions.revokeRefresh(target.getId());
            users.updatePassword(target.getId(), temporaryHash, true, adminId);
            return new PasswordReset(temporaryPassword);
        });
    }

    public AuthTokens.Pair refresh(String refreshToken) {
        var claims = tokens.decodeRefresh(refreshToken);
        return transactions.execute(status -> {
            var user = lockUser(claims.userId(), CommonErrorCode.UNAUTHORIZED);
            sessions.validateRefresh(user.getId(), refreshToken);
            validateTokenIssueAllowed(user);
            var pair = tokens.issue(user.getId(), user.getRole());
            sessions.rotate(user.getId(), refreshToken, pair.refreshToken(), tokens.refreshTtl());
            return pair;
        });
    }

    public void logout(Long userId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        var claims = tokens.decodeAccess(authorization.substring("Bearer ".length()));
        if (!userId.equals(claims.userId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        sessions.logout(userId, claims.jti(), Duration.between(Instant.now(), claims.expiresAt()));
    }

    public Me me(Long userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new Me(user.getId(), user.getEmail(), user.getNickname(), user.getRole());
    }

    // 운영자 계정이 이미 있으면 비밀번호는 유지하고, 잠긴 경우만 재기동으로 풀 수 있게 한다.
    @Transactional
    public void seedAdmin(String email, String password) {
        var existing = users.findByEmail(normalize(email));
        if (existing.isPresent()) {
            var admin = existing.get();
            if (!Roles.ADMIN.equals(admin.getRole())) {
                throw new IllegalStateException("운영자 이메일에 일반 계정이 있습니다");
            }
            if (admin.isLocked()) {
                users.resetLoginFailures(admin.getId());
            }
            return;
        }
        var bytes = passwordBytes(password);
        if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("운영자 비밀번호는 8~72바이트여야 합니다");
        }
        users.save(new User(normalize(email), passwords.encode(password), "운영자", Roles.ADMIN));
    }

    // 비밀번호가 맞으면 사용자를 돌려주고, 틀리면 실패 횟수를 올린다. 잠긴 계정은 맞아도 거절한다.
    private User authenticate(String email, String password) {
        validatePasswordBytes(password);
        var user = users.findByEmail(normalize(email)).orElse(null);
        if (user == null) {
            passwords.matches(password, dummyPasswordHash);
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        validateUnlocked(user);
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw loginFailure(user);
        }
        return user;
    }

    // Note: BCrypt 비교는 잠금 밖에서 수행하고 발급·폐기만 같은 사용자 행으로 직렬화한다.
    // 실패 횟수 갱신은 이 트랜잭션 밖이므로 인증 예외로 롤백되지 않는다.
    private User lockUser(Long userId, CommonErrorCode missingError) {
        return users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(missingError));
    }

    private void validateUnchangedPassword(User authenticated, User current) {
        if (!authenticated.getPasswordHash().equals(current.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
    }

    private void validateUnlocked(User user) {
        if (user.isLocked()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }
    }

    private void validateTokenIssueAllowed(User user) {
        validateUnlocked(user);
        if (user.isPasswordChangeRequired()) {
            throw new BusinessException(UserErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
    }

    private BusinessException loginFailure(User user) {
        users.recordLoginFailure(user.getId(), maxLoginFailures);
        var locked = users.findById(user.getId()).map(User::isLocked).orElse(false);
        return new BusinessException(locked ? UserErrorCode.ACCOUNT_LOCKED : UserErrorCode.INVALID_CREDENTIALS);
    }

    private String generateTemporaryPassword() {
        var password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
        for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
            password.append(TEMPORARY_PASSWORD_ALPHABET.charAt(random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordBytes(String password) {
        if (passwordBytes(password) > MAX_PASSWORD_BYTES) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateNewPassword(String password) {
        var bytes = passwordBytes(password);
        if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private int passwordBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }
}
