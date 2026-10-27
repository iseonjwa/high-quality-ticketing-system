package iseonjwa.user.presentation;

import iseonjwa.common.response.ApiResponse;
import iseonjwa.user.application.AuthService;
import iseonjwa.user.presentation.AuthDtos.*;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/users")
    public ResponseEntity<?> signup(@Valid @RequestBody Signup request) {
        return ResponseEntity.status(201).body(ApiResponse.success(201, auth.signup(request.email(), request.password(), request.nickname()), MDC.get("traceId")));
    }
    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody Login request) {
        return ApiResponse.success(200, auth.login(request.email(), request.password()), MDC.get("traceId"));
    }
    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@Valid @RequestBody Refresh request) {
        return ApiResponse.success(200, auth.refresh(request.refreshToken()), MDC.get("traceId"));
    }
    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestAttribute Long currentUserId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.logout(currentUserId, authorization);
        return ApiResponse.success(200, null, MDC.get("traceId"));
    }
    @GetMapping("/users/me")
    public ApiResponse<?> me(@RequestAttribute Long currentUserId) {
        return ApiResponse.success(200, auth.me(currentUserId), MDC.get("traceId"));
    }
}
