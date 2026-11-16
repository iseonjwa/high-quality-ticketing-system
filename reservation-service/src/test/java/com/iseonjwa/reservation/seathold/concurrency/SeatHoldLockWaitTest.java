package com.iseonjwa.reservation.seathold.concurrency;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldErrorCode;
import com.iseonjwa.reservation.seathold.application.SyncHoldOutcome;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 행 잠금을 짧게만 기다리는 선점(ADR 0061, lock_timeout 50ms)을 기본 빌드에서 확인한다. 여러 스레드를 겨루는 확인은
 * C3이 맡고, 여기서는 잠긴 좌석 한 건과 잠기지 않은 좌석 한 건만 본다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldLockWaitTest {
    private static final long USER_ID = 261_000L;
    // 옛 대기 상한 500ms보다 작다. 500ms일 때는 602ms가 걸려 이 값을 넘었다
    private static final long WAIT_LIMIT_MILLIS = 400;
    // SyncSeatHoldService.LOCK_TIMEOUT과 같은 값이다
    private static final long LOCK_TIMEOUT_MILLIS = 50;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncSeatHoldService seatHold;
    @Autowired
    PlatformTransactionManager transactionManager;

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
    void 다른_트랜잭션이_오래_잠근_좌석은_짧게만_기다리고_409로_거절하고_잠기지_않은_좌석은_잡는다() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, 2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var locker = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM schedule_seats WHERE id = ? FOR UPDATE", Long.class, seats.get(0));
            locked.countDown();
            await(release);
            status.setRollbackOnly();
        }));
        locker.start();
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            var started = System.nanoTime();
            assertThatThrownBy(() -> seatHold.hold(command(scheduleId, seats)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(SeatHoldErrorCode.SEAT_ALREADY_HELD));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(WAIT_LIMIT_MILLIS);

            // 잠긴 좌석이 섞인 요청은 전부 롤백되어 잠기지 않은 좌석도 그대로 AVAILABLE이다
            assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");
            assertThat(seatHold.hold(command(scheduleId, List.of(seats.get(1))))).isInstanceOf(SyncHoldOutcome.Held.class);
        } finally {
            release.countDown();
            locker.join();
        }
        // 잠금을 쥔 쪽이 롤백해 좌석은 비어 있다. 잠금을 50ms보다 오래 쥐면 빈 좌석에도 409를 준다(ADR 0059의 대가)
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    }

    // ADR 0062 1번. 이미 잡힌 좌석이 섞인 요청은 좌석 UPDATE까지 가지 않아, 다른 트랜잭션이 잠근 빈 좌석도 기다리지 않는다
    @Test
    void 이미_잡힌_좌석이_섞인_요청은_잠긴_빈_좌석을_기다리지_않고_409로_거절한다() throws Exception {
        var scheduleId = fixture.createOnSaleSchedule();
        var seats = fixture.createSeats(scheduleId, 2);
        seatHold.hold(new HoldSeatsCommand(USER_ID + 1, UUID.randomUUID(), scheduleId, List.of(seats.get(0))));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var locker = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM schedule_seats WHERE id = ? FOR UPDATE", Long.class, seats.get(1));
            locked.countDown();
            await(release);
            status.setRollbackOnly();
        }));
        locker.start();
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            var started = System.nanoTime();
            assertThatThrownBy(() -> seatHold.hold(command(scheduleId, seats)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(SeatHoldErrorCode.SEAT_ALREADY_HELD));
            // Note: 잠긴 좌석을 한 번이라도 기다렸다면 lock_timeout(50ms) 아래로 끝날 수 없다
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(LOCK_TIMEOUT_MILLIS);
        } finally {
            release.countDown();
            locker.join();
        }
    }

    private static HoldSeatsCommand command(Long scheduleId, List<Long> seatIds) {
        return new HoldSeatsCommand(USER_ID, UUID.randomUUID(), scheduleId, seatIds);
    }

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호가 오지 않았다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
