package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleSeatsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 좌석 조회 경량화 단계(P4a~P4c, 측정 브랜치 전용). 단계마다 기존 API 테스트를 그대로 다시 돌리고,
// 기존 경로(엔티티)와 JSON이 글자 하나까지 같은지 본다. k6 K1은 응답 문자열에서 "available":true를 찾으므로 모양이 같아야 한다
class ScheduleSeatApiP4Test {

    abstract static class StepTest extends ScheduleSeatApiTest {
        @Autowired
        ScheduleSeatQueryService service;
        @Autowired
        JsonMapper jsonMapper;

        @Test
        void 기존_엔티티_경로와_JSON이_같다() {
            jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));
            jdbc.update("UPDATE schedule_seats SET grade = 'LOTTERY' WHERE id = ?", seatIdOf("B", 1, 2));
            var now = Instant.parse("2026-11-06T00:00:00Z");

            var expected = ScheduleSeatsResponse.of(scheduleId, now, service.getSeats(scheduleId));
            var actual = ScheduleSeatsResponse.fromRows(scheduleId, now, service.getSeatMap(scheduleId));

            assertEquals(jsonMapper.writeValueAsString(expected), jsonMapper.writeValueAsString(actual));
        }
    }

    @TestPropertySource(properties = "reservation.seat-map.mode=DTO")
    static class Dto extends StepTest {
    }

    @TestPropertySource(properties = "reservation.seat-map.mode=LAYOUT_CACHE")
    static class LayoutCache extends StepTest {

        @Test
        void 배치를_캐시해도_상태는_요청마다_새로_읽는다() throws Exception {
            assertTrue(firstSeatOfB().get("available").asBoolean());

            jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));

            assertFalse(firstSeatOfB().get("available").asBoolean());
        }

        @Test
        void 배치를_캐시한_뒤_지운_회차도_404다() throws Exception {
            assertEquals(200, get("/api/v1/schedules/" + scheduleId + "/seats").status());

            jdbc.update("UPDATE schedules SET deleted_at = now() WHERE id = ?", scheduleId);

            assertEquals(404, get("/api/v1/schedules/" + scheduleId + "/seats").status());
        }

        tools.jackson.databind.JsonNode firstSeatOfB() throws Exception {
            return get("/api/v1/schedules/" + scheduleId + "/seats").json()
                    .get("data").get("sections").get(1).get("seats").get(0);
        }
    }

    @TestPropertySource(properties = {"reservation.seat-map.mode=STATE_CACHE", "reservation.seat-map.state-ttl=1s"})
    static class StateCache extends StepTest {

        @Test
        void 상태는_TTL_동안_같은_값을_쓰고_지나면_다시_읽는다() throws Exception {
            assertTrue(firstSeatOfB().get("available").asBoolean());

            jdbc.update("UPDATE schedule_seats SET status = 'HELD' WHERE id = ?", seatIdOf("B", 1, 1));

            // Note: P4c의 트레이드오프다. TTL 안에서는 이미 잡힌 좌석이 고를 수 있는 것으로 보인다
            assertTrue(firstSeatOfB().get("available").asBoolean());
            Thread.sleep(1_200);
            assertFalse(firstSeatOfB().get("available").asBoolean());
        }

        tools.jackson.databind.JsonNode firstSeatOfB() throws Exception {
            return get("/api/v1/schedules/" + scheduleId + "/seats").json()
                    .get("data").get("sections").get(1).get("seats").get(0);
        }
    }
}
