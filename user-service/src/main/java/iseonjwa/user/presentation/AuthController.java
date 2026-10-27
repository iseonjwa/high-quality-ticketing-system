package iseonjwa.user.presentation;

import iseonjwa.common.response.ApiResponse;
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
        return ResponseEntity.status(201).body(ApiResponse.success(201, result, traceId()));
    }

    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody Login request) {
        return ApiResponse.success(200, auth.login(request.email(), request.password()), traceId());
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@Valid @RequestBody Refresh request) {
        return ApiResponse.success(200, auth.refresh(request.refreshToken()), traceId());
    }

    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestAttribute Long currentUserId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.logout(currentUserId, authorization);
        return ApiResponse.success(200, null, traceId());
    }

    @PostMapping("/auth/password-change")
    public ApiResponse<?> changePassword(@Valid @RequestBody PasswordChange request) {
        auth.changePassword(request.email(), request.currentPassword(), request.newPassword());
        return ApiResponse.success(200, null, traceId());
    }

    @GetMapping("/users/me")
    public ApiResponse<?> me(@RequestAttribute Long currentUserId) {
        return ApiResponse.success(200, auth.me(currentUserId), traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}
