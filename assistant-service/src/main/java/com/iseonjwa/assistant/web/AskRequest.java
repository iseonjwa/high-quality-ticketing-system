package com.iseonjwa.assistant.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "1자 이상 500자 이하로 입력해 주세요")
        @Size(max = 500, message = "1자 이상 500자 이하로 입력해 주세요")
        String question) {
}
