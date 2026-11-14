package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.reservation.seathold.application.AsyncSeatHoldService;
import com.iseonjwa.reservation.seathold.application.HoldSeatsCommand;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import com.iseonjwa.reservation.seathold.application.SeatHolding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ISE-17 완료 기준을 실제 Kafka로 확인한다. 접수 → Outbox 릴레이 → {@code seat-hold.request} → 소비자 → 결과까지 전 구간이다.
 *
 * <p>Note: 브로커가 있어야 해서 CI에서는 돌지 않는다. 로컬 브로커를 띄우고 환경변수로 켠다.
 * <pre>
 * docker compose up -d kafka
 * SEAT_HOLD_KAFKA_IT=true ./gradlew :reservation-service:test --tests '*SeatHoldKafkaFlowTest'
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
                // config-repo/reservation-service.yml과 같은 값이다. 테스트 설정에는 delivery.timeout.ms만 있어 producer가 뜨지 않는다
                "spring.kafka.producer.properties.request.timeout.ms=5000",
                "spring.kafka.producer.properties.linger.ms=0",
                "messaging.outbox.relay-enabled=true"})
@ActiveProfiles("test")
class SeatHoldKafkaFlowTest {
    private static final long USER = 81L;
    private static final long OTHER_USER = 82L;
    private static final String TITLE = "ISE-17 Kafka 흐름 테스트";
    private static final Duration WAIT = Duration.ofSeconds(40);

    private final List<Long> createdVenueIds = new ArrayList<>();
    private final List<UUID> createdRequestIds = new ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AsyncSeatHoldService asyncService;
    @Autowired
    SeatHoldRequestProcessor processor;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;
    @MockitoSpyBean
    SeatHolding seatHolding;

    private Long scheduleId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        scheduleId = createSchedule();
        seatIds = createSeats(scheduleId, 4);
    }

    @AfterEach
    void cleanUp() {
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

    // 완료 기준 1
    @Test
    void 접수한_요청은_Kafka를_거쳐_HELD가_되고_같은_좌석의_뒤_요청은_SEAT_ALREADY_HELD가_된다() {
        // 소비자를 잠시 멈춰, 두 요청 모두 좌석이 AVAILABLE일 때 접수되게 한다. 같은 구역 키라 같은 순서로 처리된다
        seatHoldContainers().forEach(MessageListenerContainer::stop);
        var first = accept(USER, List.of(seatIds.get(0)));
        var second = accept(OTHER_USER, List.of(seatIds.get(0)));
        seatHoldContainers().forEach(MessageListenerContainer::start);

        waitUntil(() -> !"REQUESTED".equals(status(first)) && !"REQUESTED".equals(status(second)));

        assertEquals("HELD", status(first));
        assertEquals("HELD", seatStatus(seatIds.get(0)));
        assertEquals("REJECTED", status(second));
        assertEquals("SEAT_ALREADY_HELD", reason(second));
        assertEquals(0, heldCount(OTHER_USER));
    }

    // 완료 기준 2 (E15)
    @Test
    void 소비_중_예외가_나면_3회_재시도한_뒤_DLQ를_거쳐_PROCESSING_FAILED가_된다() {
        var failing = UUID.randomUUID();
        doThrow(new IllegalStateException("ISE-17 테스트가 넣은 처리 실패"))
                .when(seatHolding).hold(eq(failing), any(), any(), any(), any(), any());
        var startedAt = Instant.now();

        asyncService.accept(new HoldSeatsCommand(USER, failing, scheduleId, List.of(seatIds.get(2))));
        createdRequestIds.add(failing);
        waitUntil(() -> "REJECTED".equals(status(failing)));

        assertEquals("PROCESSING_FAILED", reason(failing));
        // 첫 시도 1번 + 재시도 3번(1·2·4초)
        verify(seatHolding, times(4)).hold(eq(failing), any(), any(), any(), any(), any());
        var elapsed = Duration.between(startedAt, Instant.now());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(7)) >= 0, "재시도 간격(1+2+4초)보다 빨리 끝났다: " + elapsed);
        assertEquals("AVAILABLE", seatStatus(seatIds.get(2)));
    }

    // 완료 기준 3
    @Test
    void TIMEOUT으로_닫힌_요청의_메시지가_늦게_도착해도_좌석을_잡지_않고_건너뛴다() throws Exception {
        var requestId = UUID.randomUUID();
        createdRequestIds.add(requestId);
        // 접수 뒤 메시지가 오래 늦은 상황이다. 행만 만들어 두고 시한 정리로 닫은 뒤 메시지를 직접 보낸다
        jdbc.update("INSERT INTO seat_hold_requests (request_id, user_id, schedule_id, seat_ids, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?::bigint[], 'REQUESTED', now() - interval '40 seconds', now())",
                requestId, USER, scheduleId, "{" + seatIds.get(3) + "}");
        processor.rejectTimedOut(500);
        assertEquals("TIMEOUT", reason(requestId));

        var eventId = UUID.randomUUID();
        kafka.send(SeatHoldRequestListener.TOPIC, scheduleId + ":A", envelope(eventId, requestId, seatIds.get(3)))
                .get();
        waitUntil(() -> processedCount(eventId) == 1);

        assertEquals("REJECTED", status(requestId));
        assertEquals("TIMEOUT", reason(requestId));
        assertEquals("AVAILABLE", seatStatus(seatIds.get(3)));
        assertEquals(0, heldCount(USER));
    }

    private UUID accept(long userId, List<Long> seats) {
        var requestId = asyncService.accept(new HoldSeatsCommand(userId, UUID.randomUUID(), scheduleId, seats));
        createdRequestIds.add(requestId);
        return requestId;
    }

    private List<MessageListenerContainer> seatHoldContainers() {
        return listenerRegistry.getAllListenerContainers().stream()
                .filter(container -> List.of(container.getContainerProperties().getTopics())
                        .contains(SeatHoldRequestListener.TOPIC))
                .toList();
    }

    private String envelope(UUID eventId, UUID requestId, Long seatId) {
        return """
                {"eventId":"%s","eventType":"SeatHoldRequested","version":1,"occurredAt":"%s",
                 "producer":"reservation-service","traceId":null,
                 "payload":{"requestId":"%s","userId":%d,"scheduleId":%d,"section":"A","seatIds":[%d],
                            "entryTokenJti":null,"requestedAt":"%s"}}
                """.formatted(eventId, Instant.now(), requestId, USER, scheduleId, seatId, Instant.now());
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

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer_group = ?",
                Integer.class, eventId, SeatHoldRequestProcessor.CONSUMER_GROUP);
    }

    private String status(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM seat_hold_requests WHERE request_id = ?", String.class,
                requestId);
    }

    private String reason(UUID requestId) {
        return jdbc.queryForObject("SELECT reason_code FROM seat_hold_requests WHERE request_id = ?", String.class,
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

    private Long createSchedule() {
        var venueId = jdbc.queryForObject("INSERT INTO venues (name, created_at, updated_at)"
                + " VALUES ('ISE-17 Kafka 테스트 공연장', now(), now()) RETURNING id", Long.class);
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
