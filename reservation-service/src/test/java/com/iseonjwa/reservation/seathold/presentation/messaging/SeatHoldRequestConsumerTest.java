package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.queue.domain.EntryTokenJti;
import com.iseonjwa.reservation.seathold.application.AsyncSeatHoldService;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import com.iseonjwa.reservation.seathold.application.SyncSeatHoldService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 접수형 소비자(ISE-17)를 실제 PostgreSQL·Redis로 확인한다. 브로커는 없어서, 접수가 Outbox에 남긴 메시지 원문을
// 리스너에 그대로 넘긴다. 그래서 접수가 쓰는 봉투와 소비자가 읽는 봉투가 맞는지도 함께 확인된다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class SeatHoldRequestConsumerTest {
    private static final long USER = 71L;
    private static final long OTHER_USER = 72L;
    private static final String TITLE = "ISE-17 소비자 테스트";

    private final List<Long> createdVenueIds = new ArrayList<>();
    private final List<UUID> createdRequestIds = new ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    AsyncSeatHoldService asyncService;
    @Autowired
    SyncSeatHoldService syncService;
    @Autowired
    SeatHoldRequestProcessor processor;
    @Autowired
    SeatHoldRequestListener listener;
    @Autowired
    SeatHoldRequestDlqListener dlqListener;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MeterRegistry meters;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule(4);
        seatIds = createSeats(scheduleId, 4);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(issuedKey());
        for (var requestId : createdRequestIds) {
            jdbc.update("DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM outbox_events"
                    + " WHERE payload -> 'payload' ->> 'requestId' = ?)", requestId.toString());
            jdbc.update("DELETE FROM outbox_events WHERE payload -> 'payload' ->> 'requestId' = ?",
                    requestId.toString());
        }
        for (var venueId : createdVenueIds) {
            var scheduleIds = "(SELECT id FROM schedules WHERE venue_id = ?)";
            var reservationIds = "(SELECT id FROM reservations WHERE schedule_id IN " + scheduleIds + ")";
            jdbc.update("DELETE FROM seat_hold_requests WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM reservation_status_histories WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservation_seats WHERE reservation_id IN " + reservationIds, venueId);
            jdbc.update("DELETE FROM reservations WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM user_schedule_quotas WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedule_seats WHERE schedule_id IN " + scheduleIds, venueId);
            jdbc.update("DELETE FROM schedules WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", venueId);
            jdbc.update("DELETE FROM venues WHERE id = ?", venueId);
        }
        jdbc.update("DELETE FROM concerts WHERE title = ? AND id NOT IN (SELECT concert_id FROM schedules)", TITLE);
    }

    @Test
    void 접수한_요청을_소비하면_좌석을_잡고_HELD로_닫는다() {
        var requestId = accept(USER, List.of(seatIds.get(0), seatIds.get(1)), null);

        listener.onMessage(message(requestId));

        assertEquals("HELD", status(requestId));
        assertEquals("HELD", seatStatus(seatIds.get(0)));
        assertEquals("HELD", seatStatus(seatIds.get(1)));
        assertEquals(2, heldCount(USER));
        var reservationId = jdbc.queryForObject("SELECT reservation_id FROM seat_hold_requests WHERE request_id = ?",
                Long.class, requestId);
        assertEquals(USER, jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?", Long.class,
                reservationId));
    }

    @Test
    void 선점_만료_시각은_접수가_아니라_처리_시점부터_센다() {
        var requestId = accept(USER, List.of(seatIds.get(0)), null);
        // 접수 뒤 20초가 지난 요청이다. 시한(30초) 안이라 처리한다
        jdbc.update("UPDATE seat_hold_requests SET created_at = created_at - interval '20 seconds' WHERE request_id = ?",
                requestId);
        var before = Instant.now();

        listener.onMessage(message(requestId));

        var expiresAt = jdbc.queryForObject("SELECT r.hold_expires_at FROM reservations r JOIN seat_hold_requests q"
                + " ON q.reservation_id = r.id WHERE q.request_id = ?", java.sql.Timestamp.class, requestId).toInstant();
        // 회차 hold_minutes 기본 5분. 접수 시각 기준이면 before + 5분보다 약 20초 이르다
        assertTrue(!expiresAt.isBefore(before.plus(Duration.ofMinutes(5)).minusSeconds(2)), expiresAt.toString());
    }

    @Test
    void 그사이_다른_선점이_잡은_좌석이면_좌석과_매수를_바꾸지_않고_SEAT_ALREADY_HELD로_닫는다() {
        var requestId = accept(USER, List.of(seatIds.get(0), seatIds.get(1)), null);
        syncService.hold(new HoldSeatsCommand(OTHER_USER, UUID.randomUUID(), scheduleId, List.of(seatIds.get(1))));
        var conflicts = count("seat_conflict");

        listener.onMessage(message(requestId));

        assertRejected(requestId, "SEAT_ALREADY_HELD");
        assertEquals("AVAILABLE", seatStatus(seatIds.get(0)));
        assertEquals(0, heldCount(USER));
        assertEquals(conflicts + 1, count("seat_conflict"));
        assertTrue(processed(requestId), "거절도 처리 기록을 남겨 다시 전달돼도 건너뛴다");
    }

    @Test
    void 매수_한도를_넘으면_PURCHASE_LIMIT_EXCEEDED로_닫는다() {
        jdbc.update("UPDATE schedules SET max_per_user = 2 WHERE id = ?", scheduleId);
        var first = accept(USER, List.of(seatIds.get(0), seatIds.get(1)), null);
        var second = accept(USER, List.of(seatIds.get(2)), null);

        listener.onMessage(message(first));
        listener.onMessage(message(second));

        assertEquals("HELD", status(first));
        assertRejected(second, "PURCHASE_LIMIT_EXCEEDED");
        assertEquals("AVAILABLE", seatStatus(seatIds.get(2)));
        assertEquals(2, heldCount(USER));
    }

    @Test
    void 같은_메시지를_두_번_받아도_한_번만_처리한다() {
        var requestId = accept(USER, List.of(seatIds.get(0)), null);
        var message = message(requestId);

        listener.onMessage(message);
        listener.onMessage(message);

        assertEquals("HELD", status(requestId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE schedule_id = ?", Integer.class,
                scheduleId));
        assertEquals(1, heldCount(USER));
    }

    @Test
    void 처리_시작_때_30초가_지났으면_좌석을_잠그지_않고_TIMEOUT으로_닫는다() {
        var requestId = accept(USER, List.of(seatIds.get(0)), null);
        makeOld(requestId);
        var late = count("late");

        listener.onMessage(message(requestId));

        assertRejected(requestId, "TIMEOUT");
        assertEquals("AVAILABLE", seatStatus(seatIds.get(0)));
        assertEquals(0, heldCount(USER));
        assertEquals(late + 1, count("late"));
    }

    @Test
    void 시한_정리로_TIMEOUT이_된_요청에_메시지가_늦게_와도_좌석을_잡지_않는다() {
        var requestId = accept(USER, List.of(seatIds.get(0)), null);
        makeOld(requestId);

        processor.rejectTimedOut(500);
        listener.onMessage(message(requestId));

        assertRejected(requestId, "TIMEOUT");
        assertEquals("AVAILABLE", seatStatus(seatIds.get(0)));
        assertTrue(processed(requestId));
    }

    @Test
    void 시한_정리는_소비자가_잠근_요청을_기다리지_않고_건너뛴다() throws Exception {
        var locked = accept(USER, List.of(seatIds.get(0)), null);
        var free = accept(OTHER_USER, List.of(seatIds.get(1)), null);
        makeOld(locked);
        makeOld(free);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("SELECT 1 FROM seat_hold_requests WHERE request_id = ? FOR UPDATE", locked);
                acquired.countDown();
                await(release);
            }));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));

            var startedAt = System.nanoTime();
            processor.rejectTimedOut(500);
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals("REQUESTED", status(locked));
            assertRejected(free, "TIMEOUT");
            assertTrue(elapsedMillis < 1_000, "잠긴 행을 기다리지 않아야 한다: " + elapsedMillis + "ms");
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }

        processor.rejectTimedOut(500);
        assertRejected(locked, "TIMEOUT");
    }

    @Test
    void 좌석_잠금을_시한_안에_얻지_못하면_기다리지_않고_SEAT_ALREADY_HELD로_닫는다() throws Exception {
        var requestId = accept(USER, List.of(seatIds.get(0)), null);
        var lockTimeouts = count("lock_timeout");
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            // 만료·취소처럼 줄 밖의 작업이 좌석 행을 쥐고 있는 상황이다
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("SELECT 1 FROM schedule_seats WHERE id = ? FOR UPDATE", seatIds.get(0));
                acquired.countDown();
                await(release);
            }));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));

            var startedAt = System.nanoTime();
            listener.onMessage(message(requestId));
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            // 소비자 잠금 시한은 100ms다. 동기형(500ms)보다 짧은지 넉넉한 상한으로 본다
            assertTrue(elapsedMillis < 450, "소비자 잠금 시한보다 오래 기다렸다: " + elapsedMillis + "ms");
        }

        assertRejected(requestId, "SEAT_ALREADY_HELD");
        assertEquals(0, heldCount(USER));
        assertEquals(lockTimeouts + 1, count("lock_timeout"));
    }

    @Test
    void HELD로_닫으면_대기열_issued에서_그_입장_토큰의_기록을_지운다() {
        var jti = issue(USER, 5_000L);
        var requestId = accept(USER, List.of(seatIds.get(0)), jti);

        listener.onMessage(message(requestId));

        assertEquals("HELD", status(requestId));
        assertNull(redis.opsForZSet().score(issuedKey(), String.valueOf(USER)));
    }

    @Test
    void 거절로_닫힌_요청의_입장_토큰은_다시_선점에_쓸_수_있다() {
        var jti = issue(USER, 6_000L);
        var rejected = accept(USER, List.of(seatIds.get(0)), jti);
        syncService.hold(new HoldSeatsCommand(OTHER_USER, UUID.randomUUID(), scheduleId, List.of(seatIds.get(0))));
        listener.onMessage(message(rejected));

        var retried = accept(USER, List.of(seatIds.get(1)), jti);
        listener.onMessage(message(retried));

        assertRejected(rejected, "SEAT_ALREADY_HELD");
        assertEquals("HELD", status(retried));
        assertTrue(redis.opsForZSet().score(issuedKey(), String.valueOf(USER)) == null);
    }

    @Test
    void DLQ로_간_요청은_PROCESSING_FAILED로_닫고_이미_닫힌_요청은_그대로_둔다() {
        var failed = accept(USER, List.of(seatIds.get(0)), null);
        var held = accept(OTHER_USER, List.of(seatIds.get(1)), null);
        listener.onMessage(message(held));

        dlqListener.onMessage(message(failed));
        dlqListener.onMessage(message(held));

        assertRejected(failed, "PROCESSING_FAILED");
        assertEquals("HELD", status(held));
    }

    @Test
    void 읽을_수_없는_메시지는_재시도_없이_DLQ로_보내고_DLQ에서는_건너뛴다() {
        assertThrows(NonRetryableMessageException.class, () -> listener.onMessage("{\"version\":1}"));
        assertThrows(NonRetryableMessageException.class, () -> listener.onMessage("not json"));

        dlqListener.onMessage("not json");
    }

    @Test
    void 결과_카운터는_요청이_없어도_모든_결과가_0부터_보인다() {
        for (var outcome : List.of("held", "seat_conflict", "lock_timeout", "limit_exceeded", "late", "failed",
                "skipped")) {
            assertFalse(meters.find("seathold.consumer.result").tag("outcome", outcome).counters().isEmpty(), outcome);
        }
        assertTrue(meters.find("seathold.consumer.delay").timer() != null);
    }

    private UUID accept(long userId, List<Long> seats, String entryTokenJti) {
        var requestId = asyncService.accept(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId, seats,
                entryTokenJti));
        createdRequestIds.add(requestId);
        return requestId;
    }

    // 접수가 Outbox에 남긴 메시지 원문이다. 릴레이가 Kafka로 보내는 값과 같다
    private String message(UUID requestId) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE topic = ?"
                + " AND payload -> 'payload' ->> 'requestId' = ?", String.class, SeatHoldRequestListener.TOPIC,
                requestId.toString());
    }

    private boolean processed(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM processed_events p"
                + " JOIN outbox_events o ON o.event_id = p.event_id WHERE p.consumer_group = ?"
                + " AND o.payload -> 'payload' ->> 'requestId' = ?)", Boolean.class,
                SeatHoldRequestProcessor.CONSUMER_GROUP, requestId.toString()));
    }

    private void makeOld(UUID requestId) {
        jdbc.update("UPDATE seat_hold_requests SET created_at = created_at - interval '40 seconds' WHERE request_id = ?",
                requestId);
    }

    private void assertRejected(UUID requestId, String reason) {
        assertEquals("REJECTED", status(requestId));
        assertEquals(reason, jdbc.queryForObject("SELECT reason_code FROM seat_hold_requests WHERE request_id = ?",
                String.class, requestId));
    }

    private String status(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?", String.class,
                requestId);
    }

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId);
    }

    private int heldCount(long userId) {
        var counts = jdbc.queryForList("SELECT held_count FROM user_schedule_quotas WHERE user_id = ?"
                + " AND schedule_id = ?", Integer.class, userId, scheduleId);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    private double count(String outcome) {
        return meters.get("seathold.consumer.result").tag("outcome", outcome).counter().count();
    }

    private String issue(long userId, long issuedAtMillis) {
        redis.opsForZSet().add(issuedKey(), String.valueOf(userId), issuedAtMillis);
        return EntryTokenJti.of(scheduleId, userId, issuedAtMillis);
    }

    private String issuedKey() {
        return "queue:{" + scheduleId + "}:issued";
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Long createSchedule(int maxPerUser) {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-17 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, TITLE);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, max_per_user,"
                + " version, created_at, updated_at) VALUES (?, ?, now() + interval '1 day',"
                + " now() - interval '1 minute', ?, 0, now(), now()) RETURNING id", Long.class, concertId, venueId,
                maxPerUser);
    }

    private List<Long> createSeats(Long scheduleId, int count) {
        var venueId = jdbc.queryForObject("SELECT venue_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        var ids = new ArrayList<Long>();
        for (var i = 0; i < count; i++) {
            var venueSeatId = jdbc.queryForObject("INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade,"
                    + " created_at, updated_at) VALUES (?, 'A', 1, ?, 'VIP', now(), now()) RETURNING id",
                    Long.class, venueId, i + 1);
            ids.add(jdbc.queryForObject("INSERT INTO schedule_seats (schedule_id, venue_seat_id, section, grade, price,"
                    + " status, created_at, updated_at) VALUES (?, ?, 'A', 'VIP', 165000, 'AVAILABLE', now(), now())"
                    + " RETURNING id", Long.class, scheduleId, venueSeatId));
        }
        return ids;
    }
}
