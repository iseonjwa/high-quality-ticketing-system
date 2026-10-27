package com.iseonjwa.common.web.testapp;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * common-web을 의존성으로만 가진 서비스를 흉내 낸다. 자동 설정이 적용되는지 확인하는 용도다.
 */
@SpringBootApplication
public class TestApplication {

    @RestController
    static class SampleController {

        @GetMapping("/sample/ok")
        SuccessResponse<String> ok() {
            return SuccessResponse.ok("hello");
        }

        @GetMapping("/sample/business-error")
        SuccessResponse<Void> businessError() {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        @GetMapping("/sample/unexpected-error")
        SuccessResponse<Void> unexpectedError() {
            throw new IllegalStateException("boom");
        }

        @PostMapping("/sample/validate")
        SuccessResponse<Void> validate(@Valid @RequestBody SampleRequest request) {
            return SuccessResponse.ok(null);
        }
    }

    record SampleRequest(@NotBlank(message = "필수 값입니다") String name) {
    }
}
