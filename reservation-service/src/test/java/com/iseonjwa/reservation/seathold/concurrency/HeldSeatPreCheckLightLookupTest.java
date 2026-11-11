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
 * 선점 사전 거절의 가벼운 멱등키 조회(트랜잭션 없이 있는지만 봄)와 표본 검증(측정 전용).
 * 표본 비율 1이면 거른 요청마다 DB를 다시 읽어, 낡은 캐시로 거른 잘못된 409를 센다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "seathold.precheck.enabled=true", "seathold.precheck.light-key-lookup=true",
                "seathold.precheck.verify-rate=1"})
@ActiveProfiles("test")
class HeldSeatPreCheckLightLookupTest {
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
    void 가벼운_조회로도_같은_멱등키는_재응답하고_진짜_선점된_좌석은_잘못된_거절로_세지_않는다() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, 1);
        var first = new HoldSeatsCommand(790_201L, UUID.randomUUID(), scheduleId, seats);
        var held = seatHold.hold(first);
        Thread.sleep(1_100);
        seatQuery.getSeatMap(scheduleId);
        var falseBefore = count("seathold.precheck.false.rejected");

        var loser = new HoldSeatsCommand(790_202L, UUID.randomUUID(), scheduleId, seats);
        var exception = assertThrows(BusinessException.class, () -> seatHold.hold(loser));
        assertEquals(SeatHoldErrorCode.SEAT_ALREADY_HELD, exception.getErrorCode());
        assertEquals(falseBefore, count("seathold.precheck.false.rejected"));

        assertEquals(held.reservationId(), seatHold.hold(first).reservationId());
    }

    @Test
    void 캐시가_낡아_풀린_좌석을_거르면_잘못된_거절로_센다() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, 1);
        seatHold.hold(new HoldSeatsCommand(790_203L, UUID.randomUUID(), scheduleId, seats));
        Thread.sleep(1_100);
        seatQuery.getSeatMap(scheduleId);
        // 캐시가 "잡힘"을 들고 있는 동안 좌석만 풀린 상황을 만든다(만료가 막 돈 직후와 같다)
        jdbc.update("UPDATE schedule_seats SET status = 'AVAILABLE' WHERE id = ?", seats.get(0));
        var falseBefore = count("seathold.precheck.false.rejected");

        assertThrows(BusinessException.class,
                () -> seatHold.hold(new HoldSeatsCommand(790_204L, UUID.randomUUID(), scheduleId, seats)));
        assertEquals(falseBefore + 1, count("seathold.precheck.false.rejected"));
    }

    private double count(String name) {
        return registry.counter(name).count();
    }
}
