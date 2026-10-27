package com.iseonjwa.common.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseFormatTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 성공_응답은_status_message_data_traceId_네_키다() {
        var json = mapper.valueToTree(SuccessResponse.of(201, "선점되었습니다", Map.of("status", "HELD"), "trace-1"));

        assertEquals(4, json.size());
        assertEquals(201, json.get("status").asInt());
        assertEquals("선점되었습니다", json.get("message").asString());
        assertEquals("HELD", json.get("data").get("status").asString());
        assertEquals("trace-1", json.get("traceId").asString());
    }

    @Test
    void 본문이_없는_성공도_data를_null로_내보낸다() {
        var json = mapper.valueToTree(SuccessResponse.of(200, "로그아웃되었습니다", null, null));

        assertTrue(json.has("data"));
        assertTrue(json.get("data").isNull());
        assertTrue(json.has("traceId"));
    }

    @Test
    void 성공_응답은_2xx이며_본문이_있는_상태만_허용한다() {
        assertThrows(IllegalArgumentException.class, () -> SuccessResponse.of(204, "없음", null, null));
        assertThrows(IllegalArgumentException.class, () -> SuccessResponse.of(404, "실패", null, null));
    }

    @Test
    void 업무_실패는_errors_없이_status_code_message_traceId_네_키다() {
        var json = mapper.valueToTree(FailureResponse.of(CommonErrorCode.NOT_FOUND, null, "trace-2"));

        assertEquals(4, json.size());
        assertEquals(404, json.get("status").asInt());
        assertEquals("NOT_FOUND", json.get("code").asString());
        assertFalse(json.has("errors"));
        assertFalse(json.has("data"));
    }

    @Test
    void 검증_실패는_필드와_사유를_내보내며_목록을_복사한다() {
        var errors = new ArrayList<>(List.of(new FieldError("seatIds", "1~4개여야 합니다")));
        var response = FailureResponse.of(CommonErrorCode.VALIDATION_FAILED, errors, null);
        errors.clear();
        var json = mapper.valueToTree(response);

        assertEquals(5, json.size());
        assertEquals("seatIds", json.get("errors").get(0).get("field").asString());
        assertEquals("1~4개여야 합니다", json.get("errors").get(0).get("reason").asString());
        assertNull(response.traceId());
    }
}
