package iseonjwa.user.presentation;

import iseonjwa.common.response.SuccessResponse;
import iseonjwa.user.application.AuthService;
import iseonjwa.user.presentation.AuthDtos.Login;
import iseonjwa.user.presentation.AuthDtos.PasswordChange;
import iseonjwa.user.presentation.AuthDtos.Refresh;
import iseonjwa.user.presentation.AuthDtos.Signup;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/users")
    public ResponseEntity<?> signup(@Valid @RequestBody Signup request) {
        var result = auth.signup(request.email(), request.password(), request.nickname());
        return ResponseEntity.status(201).body(SuccessResponse.of(201, "가입되었습니다", result, traceId()));
    }

    @PostMapping("/auth/login")
    public SuccessResponse<?> login(@Valid @RequestBody Login request) {
        return SuccessResponse.of(200, "로그인되었습니다", auth.login(request.email(), request.password()), traceId());
    }

    @PostMapping("/auth/refresh")
    public SuccessResponse<?> refresh(@Valid @RequestBody Refresh request) {
        return SuccessResponse.of(200, "토큰이 재발급되었습니다", auth.refresh(request.refreshToken()), traceId());
    }

    @PostMapping("/auth/logout")
    public SuccessResponse<?> logout(@RequestAttribute Long currentUserId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.logout(currentUserId, authorization);
        return SuccessResponse.of(200, "로그아웃되었습니다", null, traceId());
    }

    @PostMapping("/auth/password-change")
    public SuccessResponse<?> changePassword(@Valid @RequestBody PasswordChange request) {
        auth.changePassword(request.email(), request.currentPassword(), request.newPassword());
        return SuccessResponse.of(200, "비밀번호가 변경되었습니다", null, traceId());
    }

    @GetMapping("/users/me")
    public SuccessResponse<?> me(@RequestAttribute Long currentUserId) {
        return SuccessResponse.of(200, "조회되었습니다", auth.me(currentUserId), traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}
