package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.reservation.seathold.application.AsyncSeatHoldService;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 접수형 직접 발행(측정 전용, ISE-18)이 Outbox 없이 실제 Kafka를 거쳐 소비자까지 가는지 본다. Outbox 릴레이를 꺼서
 * 메시지가 직접 발행으로만 갈 수 있게 한다.
 *
 * <p>Note: 브로커가 있어야 해서 CI에서는 돌지 않는다. {@link SeatHoldKafkaFlowTest}와 같은 방법으로 켠다.
 * <pre>
 * docker compose up -d kafka
 * SEAT_HOLD_KAFKA_IT=true ./gradlew :reservation-service:test --tests '*SeatHoldDirectPublishKafkaFlowTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SEAT_HOLD_KAFKA_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "spring.kafka.bootstrap-servers=localhost:19092",
                "spring.kafka.listener.auto-startup=true",
                "spring.kafka.admin.auto-create=true",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.producer.acks=all",
                "spring.kafka.producer.properties.request.timeout.ms=5000",
                "spring.kafka.producer.properties.linger.ms=0",
                "messaging.outbox.relay-enabled=false",
                "seat-hold.async.publish=direct"})
@ActiveProfiles("test")
class SeatHoldDirectPublishKafkaFlowTest {
    private static final long USER = 83L;
    private static final long OTHER_USER = 84L;
    private static final String TITLE = "ISE-18 직접 발행 Kafka 흐름 테스트";
    private static final Duration WAIT = Duration.ofSeconds(40);

    private final List<Long> createdVenueIds = new ArrayList<>();
    private Instant startedAt;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AsyncSeatHoldService asyncService;
    @Autowired
    MeterRegistry registry;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        startedAt = Instant.now();
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 2);
    }

    @AfterEach
    void cleanUp() {
        // 직접 발행은 Outbox 행이 없어 이벤트 ID로 처리 기록을 찾을 수 없다. 이 테스트가 도는 동안 쓴 기록을 지운다
        jdbc.update("DELETE FROM processed_events WHERE consumer_group = ? AND processed_at >= ?",
                SeatHoldRequestProcessor.CONSUMER_GROUP, java.sql.Timestamp.from(startedAt));
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
    void 직접_발행한_요청은_Outbox_없이_Kafka를_거쳐_HELD가_되고_예매_좌석까지_남는다() {
        var sentBefore = sentCount();

        var first = accept(USER, seatIds.get(0));
        waitUntil(() -> !"REQUESTED".equals(status(first)));
        var second = accept(OTHER_USER, seatIds.get(1));
        waitUntil(() -> !"REQUESTED".equals(status(second)));

        assertEquals("HELD", status(first));
        assertEquals("HELD", seatStatus(seatIds.get(0)));
        assertEquals(1, reservationSeatCount(first));
        assertEquals("HELD", status(second));
        assertEquals(2.0, sentCount() - sentBefore);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'SeatHoldRequested'"
                + " AND (payload -> 'payload' ->> 'scheduleId')::bigint = ?", Integer.class, scheduleId));
    }

    private UUID accept(long userId, Long seatId) {
        return asyncService.accept(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId, List.of(seatId)));
    }

    private double sentCount() {
        return registry.get("seathold.direct_publish").tag("result", "sent").counter().count();
    }

    private void waitUntil(Supplier<Boolean> condition) {
        var deadline = Instant.now().plus(WAIT);
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(WAIT + " 안에 조건을 만족하지 못했습니다");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private String status(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?", String.class,
                requestId);
    }

    private String seatStatus(Long seatId) {
        return jdbc.queryForObject("SELECT status FROM schedule_seats WHERE id = ?", String.class, seatId);
    }

    private int reservationSeatCount(UUID requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_seats rs JOIN reservations r"
                + " ON r.id = rs.reservation_id WHERE r.request_id = ?", Integer.class, requestId);
    }

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-18 Kafka 테스트 공연장', now(), now()) RETURNING id", Long.class);
        createdVenueIds.add(venueId);
        var concertId = jdbc.queryForObject("INSERT INTO concerts (title, version, created_at, updated_at)"
                + " VALUES (?, 0, now(), now()) RETURNING id", Long.class, TITLE);
        return jdbc.queryForObject("INSERT INTO schedules (concert_id, venue_id, starts_at, open_at, version,"
                + " created_at, updated_at) VALUES (?, ?, now() + interval '1 day', now() - interval '1 minute', 0,"
                + " now(), now()) RETURNING id", Long.class, concertId, venueId);
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
