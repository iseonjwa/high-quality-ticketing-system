package com.iseonjwa.user.domain;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 잠금·임시 비밀번호 상태는 저장소 UPDATE로만 바뀌므로 테스트에서는 필드를 직접 채운다.
class UserTest {
    @Test
    void 이메일은_앞뒤_공백을_지우고_소문자로_저장한다() {
        var user = new User("  Kim@Example.COM ", "hash", "김", Roles.USER);

        assertEquals("kim@example.com", user.getEmail());
        assertEquals("kim@example.com", User.normalizeEmail("  Kim@Example.COM "));
    }

    @Test
    void 정상_계정은_토큰을_발급할_수_있다() {
        var user = user();

        assertDoesNotThrow(user::ensureUnlocked);
        assertDoesNotThrow(user::ensureTokenIssuable);
    }

    @Test
    void 잠긴_계정은_잠금으로_거절한다() {
        var user = user();
        ReflectionTestUtils.setField(user, "lockedAt", Instant.now());

        assertEquals(UserErrorCode.ACCOUNT_LOCKED, errorOf(user::ensureUnlocked));
        assertEquals(UserErrorCode.ACCOUNT_LOCKED, errorOf(user::ensureTokenIssuable));
    }

    @Test
    void 임시_비밀번호_계정은_토큰_발급만_거절한다() {
        var user = user();
        ReflectionTestUtils.setField(user, "passwordChangeRequired", true);

        assertDoesNotThrow(user::ensureUnlocked);
        assertEquals(UserErrorCode.PASSWORD_CHANGE_REQUIRED, errorOf(user::ensureTokenIssuable));
    }

    @Test
    void 잠금과_임시_비밀번호가_함께면_잠금을_먼저_알린다() {
        var user = user();
        ReflectionTestUtils.setField(user, "lockedAt", Instant.now());
        ReflectionTestUtils.setField(user, "passwordChangeRequired", true);

        assertEquals(UserErrorCode.ACCOUNT_LOCKED, errorOf(user::ensureTokenIssuable));
    }

    private User user() {
        return new User("user@test.local", "hash", "테스트", Roles.USER);
    }

    private UserErrorCode errorOf(Runnable check) {
        return (UserErrorCode) assertThrows(BusinessException.class, check::run).getErrorCode();
    }
}
