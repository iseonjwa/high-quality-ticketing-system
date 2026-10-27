package com.iseonjwa.common.web;

import com.iseonjwa.common.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class CommonWebTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void 명시적으로_공통_설정을_import한다() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void 컨텍스트와_추적정보를_정리한다() {
        MDC.clear();
        context.close();
    }

    @Test
    void 업무_예외의_HTTP상태와_응답_상태를_일치시킨다() throws Exception {
        MDC.put("traceId", "trace-test");
        var result = mvc.perform(get("/api/v1/test/business")).andReturn();
        var json = body(result, 404);
        assertEquals("NOT_FOUND", json.get("code").asString());
        assertEquals("trace-test", json.get("traceId").asString());
        assertFalse(json.has("errors"));
    }

    @Test
    void 검증_실패는_필드와_사유를_반환한다() throws Exception {
        var result = mvc.perform(post("/api/v1/test/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")).andReturn();
        var json = body(result, 400);
        assertEquals("VALIDATION_FAILED", json.get("code").asString());
        assertEquals("name", json.get("errors").get(0).get("field").asString());
        assertEquals("이름이 필요합니다", json.get("errors").get(0).get("reason").asString());
    }

    @Test
    void 잘못된_JSON은_400으로_반환한다() throws Exception {
        var result = mvc.perform(post("/api/v1/test/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{")).andReturn();
        assertEquals("VALIDATION_FAILED", body(result, 400).get("code").asString());
    }

    @Test
    void 지원하지_않는_메서드는_405와_Allow헤더를_유지한다() throws Exception {
        var result = mvc.perform(post("/api/v1/test/business")).andReturn();
        body(result, 405);
        assertTrue(result.getResponse().getHeader("Allow").contains("GET"));
    }

    @Test
    void 없는_API는_404_공통_응답을_반환한다() throws Exception {
        var result = mvc.perform(get("/api/v1/missing")).andReturn();
        assertEquals("NOT_FOUND", body(result, 404).get("code").asString());
    }

    @Test
    void 예상하지_못한_예외의_내부_상세를_노출하지_않는다() throws Exception {
        var result = mvc.perform(get("/api/v1/test/unexpected")).andReturn();
        assertEquals("INTERNAL_SERVER_ERROR", body(result, 500).get("code").asString());
        assertFalse(result.getResponse().getContentAsString().contains("private-database-detail"));
    }

    @Test
    void 정상_본문은_자동으로_감싸지_않는다() throws Exception {
        var result = mvc.perform(get("/api/v1/test/raw")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("raw", result.getResponse().getContentAsString());
    }

    @Test
    void 사용자_정보가_필요한_API에_사용자_속성이_없으면_401이다() throws Exception {
        var result = mvc.perform(get("/api/v1/test/me")).andReturn();
        assertEquals("UNAUTHORIZED", body(result, 401).get("code").asString());
        assertEquals(200, mvc.perform(get("/api/v1/test/me").requestAttr("currentUserId", 7L))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void RequireRole은_역할이_없으면_401_다른_역할이면_403_맞으면_통과시킨다() throws Exception {
        var anonymous = mvc.perform(get("/api/v1/test/admin")).andReturn();
        assertEquals("UNAUTHORIZED", body(anonymous, 401).get("code").asString());

        var user = mvc.perform(get("/api/v1/test/admin").requestAttr("currentUserRole", "USER")).andReturn();
        assertEquals("FORBIDDEN", body(user, 403).get("code").asString());

        assertEquals(200, mvc.perform(get("/api/v1/test/admin").requestAttr("currentUserRole", "ADMIN"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void RequireRole이_없는_API는_역할을_확인하지_않는다() throws Exception {
        assertEquals(200, mvc.perform(get("/api/v1/test/raw")).andReturn().getResponse().getStatus());
    }

    @Test
    void 외부_컨트롤러의_본문과_예외에는_개입하지_않는다() throws Exception {
        var result = mvc.perform(get("/actuator/sample")).andReturn();
        assertEquals("metric 1", result.getResponse().getContentAsString());
        var exception = assertThrows(jakarta.servlet.ServletException.class,
                () -> mvc.perform(get("/mock-pg/failure")));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private JsonNode body(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        var json = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(status, json.get("status").asInt());
        assertTrue(json.has("traceId"));
        assertFalse(json.has("data"));
        return json;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(CommonWebConfiguration.class)
    static class Config {
        @Bean
        external.ExternalController externalController() {
            return new external.ExternalController();
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/api/v1/test/business")
        void business() {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }

        @PostMapping("/api/v1/test/validate")
        void validate(@Valid @RequestBody NameRequest request) {}

        @GetMapping("/api/v1/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("private-database-detail");
        }

        @GetMapping("/api/v1/test/admin")
        @RequireRole("ADMIN")
        void admin() {}

        @GetMapping("/api/v1/test/me")
        void me(@RequestAttribute Long currentUserId) {}

        @GetMapping("/api/v1/test/raw")
        String raw() {
            return "raw";
        }
    }

    record NameRequest(@NotBlank(message = "이름이 필요합니다") String name) {}
}
