package iseonjwa.user.presentation;

import iseonjwa.common.identity.Roles;
import iseonjwa.common.response.ApiResponse;
import iseonjwa.common.response.BusinessException;
import iseonjwa.common.response.CommonErrorCode;
import iseonjwa.user.application.AuthService;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AuthService auth;

    public AdminUserController(AuthService auth) {
        this.auth = auth;
    }

    // 임시 비밀번호를 응답으로 한 번만 돌려준다. 사용자는 로그인 전에 비밀번호를 바꿔야 한다.
    @PostMapping("/{userId}/password-reset")
    public ResponseEntity<?> resetPassword(@PathVariable Long userId,
            @RequestAttribute Long currentUserId, @RequestAttribute String currentUserRole) {
        // Gateway가 먼저 막지만 비밀번호를 바꾸는 API라서 서비스에서도 한 번 더 확인한다.
        if (!Roles.ADMIN.equals(currentUserRole)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        var result = auth.resetPassword(currentUserId, userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(200, result, MDC.get("traceId")));
    }
}
