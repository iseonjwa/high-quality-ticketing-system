package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.concert.application.ScheduleSeatsResult;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.SeatLayoutRow;
import com.iseonjwa.reservation.concert.presentation.dto.response.ScheduleSeatsResponse;
import com.iseonjwa.reservation.concert.presentation.dto.response.SeatSectionResponse;
import com.iseonjwa.reservation.global.vo.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 좌석 배열을 직렬화한 채로 다시 쓰는 캐시(ISE-264). 응답 모양은 그대로이고 봉투(traceId·serverTime)는 요청마다 새로 쓴다
class ScheduleSeatSectionsJsonCacheTest {

    static final long SCHEDULE_A = 7L;
    static final long SCHEDULE_B = 8L;

    final JsonMapper mapper = JsonMapper.builder().build();
    final ScheduleSeatSectionsJsonCache cache = new ScheduleSeatSectionsJsonCache(mapper);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 미리_직렬화한_응답은_객체_목록을_바로_직렬화한_응답과_바이트까지_같다() {
        ScheduleSeatsResult result = result(List.of(seat(1L, "A", SeatGrade.VIP, 1, 1), seat(2L, "A", SeatGrade.VIP, 1, 2),
                seat(3L, "B", SeatGrade.S, 1, 1)), true, false, true);
        Instant now = Instant.parse("2026-11-16T00:00:00.123Z");

        byte[] before = mapper.writeValueAsBytes(SuccessResponse.of(200, "회차 좌석을 조회했습니다",
                new LegacyScheduleSeatsResponse(SCHEDULE_A, now, SeatSectionResponse.listOf(result)), "trace-1"));
        byte[] after = mapper.writeValueAsBytes(SuccessResponse.of(200, "회차 좌석을 조회했습니다",
                ScheduleSeatsResponse.of(
                        SCHEDULE_A, now, cache.get(SCHEDULE_A, result)), "trace-1"));

        assertArrayEquals(before, after);
    }

    @Test
    void 같은_결과면_좌석_배열을_다시_직렬화하지_않아도_traceId와_serverTime은_요청마다_다르다() {
        ScheduleSeatsResult result = result(List.of(seat(1L, "A", SeatGrade.VIP, 1, 1)), true);
        ScheduleSeatQueryService service = mock(ScheduleSeatQueryService.class);
        when(service.getSeats(SCHEDULE_A)).thenReturn(result);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-16T00:00:00.5Z"));
        ScheduleSeatController controller = new ScheduleSeatController(service, cache, clock);

        MDC.put("traceId", "trace-1");
        var first = controller.getSeats(SCHEDULE_A).getBody();
        MDC.put("traceId", "trace-2");
        var second = controller.getSeats(SCHEDULE_A).getBody();

        assertEquals("trace-1", first.traceId());
        assertEquals("trace-2", second.traceId());
        assertNotEquals(first.data().getServerTime(), second.data().getServerTime());
        assertNotEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(second));
        assertSame(first.data().getSections(), second.data().getSections());
    }

    // 서비스는 1초 캐시 값이 바뀌면 새 결과 객체를 준다. 그때는 좌석 배열을 새로 직렬화해야 바뀐 상태가 보인다
    @Test
    void 결과가_바뀌면_좌석_배열을_새로_직렬화한다() {
        List<SeatLayoutRow> layout = List.of(seat(1L, "A", SeatGrade.VIP, 1, 1));

        var before = cache.get(SCHEDULE_A, result(layout, true));
        var after = cache.get(SCHEDULE_A, result(layout, false));

        assertNotSame(before, after);
        assertEquals(json(List.of(section("A", SeatGrade.VIP, 1L, false))), mapper.writeValueAsString(after));
    }

    @Test
    void 다른_회차는_섞이지_않고_서로의_캐시를_밀어내지도_않는다() {
        ScheduleSeatsResult a = result(List.of(seat(1L, "A", SeatGrade.VIP, 1, 1)), true);
        ScheduleSeatsResult b = result(List.of(seat(2L, "B", SeatGrade.S, 1, 1)), false);

        var aFirst = cache.get(SCHEDULE_A, a);
        var bFirst = cache.get(SCHEDULE_B, b);
        var aAgain = cache.get(SCHEDULE_A, a);

        assertEquals(json(List.of(section("A", SeatGrade.VIP, 1L, true))), mapper.writeValueAsString(aFirst));
        assertEquals(json(List.of(section("B", SeatGrade.S, 2L, false))), mapper.writeValueAsString(bFirst));
        assertSame(aFirst, aAgain);
    }

    // 참고값 측정. 기본 빌드에서는 돌지 않는다: SEAT_JSON_BENCH=true ./gradlew :reservation-service:test --tests '*ScheduleSeatSectionsJsonCacheTest*'
    @Test
    @EnabledIfEnvironmentVariable(named = "SEAT_JSON_BENCH", matches = "true")
    void 만오천석_응답_천_번_직렬화_시간_참고값() {
        List<SeatLayoutRow> layout = new ArrayList<>();
        boolean[] available = new boolean[15_000];
        for (int i = 0; i < 15_000; i++) {
            layout.add(seat(i + 1L, "S" + (i / 1_000), SeatGrade.R, i % 1_000 / 25 + 1, i % 25 + 1));
            available[i] = i % 3 != 0;
        }
        ScheduleSeatsResult result = new ScheduleSeatsResult(layout, available);
        Instant now = Instant.parse("2026-11-16T00:00:00Z");
        int bytes = mapper.writeValueAsBytes(new LegacyScheduleSeatsResponse(1L, now, SeatSectionResponse.listOf(result))).length;

        long legacy = time(() -> mapper.writeValueAsBytes(SuccessResponse.of(200, "m",
                new LegacyScheduleSeatsResponse(1L, now, SeatSectionResponse.listOf(result)), "t")));
        long cached = time(() -> mapper.writeValueAsBytes(SuccessResponse.of(200, "m",
                ScheduleSeatsResponse.of(
                        1L, now, cache.get(1L, result)), "t")));

        System.out.printf("[SEAT_JSON_BENCH] 본문 %,d바이트, 1,000번: 객체 생성+직렬화 %,dms, 캐시한 좌석 배열 %,dms%n",
                bytes, legacy / 1_000_000, cached / 1_000_000);
    }

    private static long time(Runnable work) {
        for (int i = 0; i < 200; i++) {
            work.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            work.run();
        }
        return System.nanoTime() - start;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static SeatSectionResponse section(String name, SeatGrade grade, Long seatId, boolean available) {
        SeatLayoutRow seat = seat(seatId, name, grade, 1, 1);
        SeatSectionResponse section = SeatSectionResponse.from(seat);
        section.add(seat, available);
        return section;
    }

    private static ScheduleSeatsResult result(List<SeatLayoutRow> seats, boolean... available) {
        return new ScheduleSeatsResult(seats, available);
    }

    private static SeatLayoutRow seat(Long seatId, String section, SeatGrade grade, int rowNo, int seatNo) {
        return new SeatLayoutRow(seatId, section, grade, Money.of(165_000), rowNo, seatNo);
    }

    // 바꾸기 전 응답 모양(sections가 객체 목록)
    record LegacyScheduleSeatsResponse(Long scheduleId, Instant serverTime, List<SeatSectionResponse> sections) {
    }
}
