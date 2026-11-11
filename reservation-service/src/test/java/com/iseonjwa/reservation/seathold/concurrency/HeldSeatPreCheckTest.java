package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.concert.application.ScheduleSeatQueryService;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldErrorCode;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 선점 사전 거절(측정 브랜치 전용). 1초 상태 캐시에 잡힌 좌석은 DB에 가기 전에 409로 거르고,
 * 같은 멱등키 재요청은 지금처럼 재응답하는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "seathold.precheck.enabled=true"})
@ActiveProfiles("test")
class HeldSeatPreCheckTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    ScheduleSeatQueryService seatQuery;
    @Autowired
    MeterRegistry registry;

    private SeatHoldFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new SeatHoldFixture(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void 캐시에_잡힌_좌석은_DB_전에_409이고_같은_멱등키는_재응답한다() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, 2);
        var first = new HoldSeatsCommand(790_101L, UUID.randomUUID(), scheduleId, List.of(seats.get(0)));
        var held = seatHold.hold(first);

        // 선점 뒤 1초가 지나야 상태 캐시가 새로 읽혀 잡힌 좌석이 보인다
        Thread.sleep(1_100);
        seatQuery.getSeatMap(scheduleId);
        var before = rejectedCount();

        var loser = new HoldSeatsCommand(790_102L, UUID.randomUUID(), scheduleId, List.of(seats.get(0)));
        var exception = assertThrows(BusinessException.class, () -> seatHold.hold(loser));
        assertEquals(SeatHoldErrorCode.SEAT_ALREADY_HELD, exception.getErrorCode());
        assertEquals(before + 1, rejectedCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM seat_hold_requests WHERE request_id = ?",
                Integer.class, loser.requestId()));

        var replayed = seatHold.hold(first);
        assertEquals(held.reservationId(), replayed.reservationId());

        var other = seatHold.hold(new HoldSeatsCommand(790_102L, UUID.randomUUID(), scheduleId, List.of(seats.get(1))));
        assertEquals(List.of(seats.get(1)), other.seatIds());
    }

    private double rejectedCount() {
        return registry.counter("seathold.precheck.rejected").count();
    }
}
