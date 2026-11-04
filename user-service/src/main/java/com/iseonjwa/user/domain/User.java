package com.iseonjwa.user.domain;

import com.iseonjwa.common.persistence.BaseEntity;
import com.iseonjwa.common.response.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(nullable = false, length = 10)
    private String role;

    // 로그인 실패 횟수와 잠금은 동시 시도에도 정확하도록 저장소의 UPDATE로만 바꾼다.
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    protected User() {}

    public User(String email, String passwordHash, String nickname, String role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
    }

    // 저장과 조회가 같은 규칙을 쓰도록 이메일은 항상 이 형태로 다룬다.
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public boolean isLocked() {
        return lockedAt != null;
    }

    public void ensureUnlocked() {
        if (isLocked()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }
    }

    // 잠긴 계정과 임시 비밀번호 계정에는 토큰을 발급하지 않는다(로그인·재발급 공통).
    public void ensureTokenIssuable() {
        ensureUnlocked();
        if (passwordChangeRequired) {
            throw new BusinessException(UserErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public String getRole() { return role; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
}
