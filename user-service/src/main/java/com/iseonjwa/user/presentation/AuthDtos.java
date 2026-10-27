package com.iseonjwa.user.presentation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record Signup(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 30) String nickname) {}

    public record Login(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 72) String password) {}

    public record Refresh(@NotBlank String refreshToken) {}

    public record PasswordChange(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {}
}
