package com.iseonjwa.common.web;

import com.iseonjwa.common.web.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 형식 검사 (SA 11-2): 성공·업무 에러·검증 실패·경로 없음·405·깨진 JSON·처리 안 된 예외가
 * 모두 정해진 형태로 나오는지 본다.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CommonWebResponseFormatTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void 성공_응답은_status_message_data_traceId_형태다() {
        assertThat(mvc.get().uri("/sample/ok"))
                .hasStatus(200)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo(200))
                .hasPathSatisfying("$.data", v -> v.assertThat().isEqualTo("hello"))
                .hasPath("$.message")
                .hasPath("$.traceId");
    }

    @Test
    void 업무_예외는_에러_코드와_HTTP_상태가_같다() {
        assertThat(mvc.get().uri("/sample/business-error"))
                .hasStatus(403)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo(403))
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("FORBIDDEN"))
                .doesNotHavePath("$.errors")
                .hasPath("$.traceId");
    }

    @Test
    void 검증_실패는_errors에_필드와_사유를_담는다() {
        assertThat(mvc.post().uri("/sample/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .hasStatus(400)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.errors[0].field", v -> v.assertThat().isEqualTo("name"))
                .hasPathSatisfying("$.errors[0].reason", v -> v.assertThat().isEqualTo("필수 값입니다"));
    }

    @Test
    void 깨진_JSON도_실패_형태로_나온다() {
        assertThat(mvc.post().uri("/sample/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
                .hasStatus(400)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void 없는_경로는_NOT_FOUND다() {
        assertThat(mvc.get().uri("/no-such-path"))
                .hasStatus(404)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_METHOD_NOT_ALLOWED다() {
        assertThat(mvc.delete().uri("/sample/ok"))
                .hasStatus(405)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 처리_안_된_예외는_INTERNAL_ERROR다() {
        assertThat(mvc.get().uri("/sample/unexpected-error"))
                .hasStatus(500)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("INTERNAL_ERROR"));
    }
}
