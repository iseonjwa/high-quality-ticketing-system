package iseonjwa.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void 성공_응답은_null을_포함한_6키를_유지한다() {
        var json = mapper.valueToTree(ApiResponse.success(200, null, null));

        assertEquals(6, json.size());
        for (var key : List.of("status", "code", "message", "data", "errors", "traceId")) {
            assertTrue(json.has(key), key);
        }
        assertEquals("SUCCESS", json.get("code").asString());
        assertTrue(json.get("data").isNull());
        assertTrue(json.get("errors").isNull());
        assertTrue(json.get("traceId").isNull());
    }

    @Test
    void 결제_거절은_HTTP_성공과_업무_상태로_표현한다() {
        var json = mapper.valueToTree(ApiResponse.success(200, Map.of("status", "DECLINED"), "trace-1"));

        assertEquals(200, json.get("status").asInt());
        assertEquals("DECLINED", json.get("data").get("status").asString());
        assertEquals("trace-1", json.get("traceId").asString());
    }

    @Test
    void 업무_실패는_에러코드와_HTTP상태를_보존한다() {
        var response = ApiResponse.failure(CommonErrorCode.NOT_FOUND, null, "trace-2");

        assertEquals(404, response.status());
        assertEquals("NOT_FOUND", response.code());
        assertNull(response.data());
        assertNull(response.errors());
    }

    @Test
    void 검증_오류는_필드와_사유를_내보내며_목록을_복사한다() {
        var errors = new java.util.ArrayList<>(List.of(new FieldError("seatIds", "1~4개여야 합니다")));
        var response = ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED, errors, null);
        errors.clear();
        var json = mapper.valueToTree(response);

        assertEquals("seatIds", json.get("errors").get(0).get("field").asString());
        assertEquals("1~4개여야 합니다", json.get("errors").get(0).get("reason").asString());
    }
}
