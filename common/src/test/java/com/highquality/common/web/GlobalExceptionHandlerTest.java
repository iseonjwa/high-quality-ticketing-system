package com.highquality.common.web;

import com.highquality.common.exception.BusinessException;
import com.highquality.common.exception.CommonErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예외가 SA 11-2 실패 형태 {status, code, message, errors, traceId}로 나오는지 확인한다.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 업무_예외는_에러_코드의_상태와_코드로_나간다() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("권한이 없습니다"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void 검증_실패는_필드별_사유를_errors에_담는다() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void 깨진_JSON은_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 없는_경로는_NOT_FOUND다() throws Exception {
        mockMvc.perform(get("/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_METHOD_NOT_ALLOWED다() throws Exception {
        mockMvc.perform(post("/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 처리되지_않은_예외는_INTERNAL_ERROR다() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody NameRequest request) {
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("예상하지 못한 오류");
        }
    }

    record NameRequest(@NotBlank String name) {
    }
}
