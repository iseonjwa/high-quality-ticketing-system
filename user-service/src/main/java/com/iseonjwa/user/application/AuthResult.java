package com.iseonjwa.user.application;

public final class AuthResult {
    private AuthResult() {}

    public record SignupResult(Long userId, String email, String nickname) {}

    public record Me(Long userId, String email, String nickname, String role) {}

    public record PasswordReset(String temporaryPassword) {}
}
