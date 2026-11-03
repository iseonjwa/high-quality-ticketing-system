package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReconciliationProperties;
import com.iseonjwa.payment.reconciliation.application.ReconciliationRecorder;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService.ReconciliationFailedException;
import com.iseonjwa.payment.reconciliation.domain.Preparation;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import com.iseonjwa.payment.reconciliation.domain.ReservationView;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 예매와 대조하는 대사 규칙 RC-1~RC-6(ISE-168)을 PostgreSQL과 가짜 예매 서버로 확인한다. 예매 조회가 켜진 컨텍스트라
 * {@link FakeReservationConfiguration}이 조회 포트를 가짜 서버로 향하게 한다. 테스트는 트랜잭션 안에서 행을 넣고 롤백한다. 픽스처는 이력 없이 결제를 넣어 RC-7이 따로 잡히므로 RC-1~RC-6 건수만 센다. 기준
 * 시각을 서비스에 인자로 넘겨 {@code sleep} 없이 허용 지연(10분)을 넘긴다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "payment.reconciliation.reservation.enabled=true", "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"})
@ActiveProfiles("test")
@Import(FakeReservationConfiguration.class)
@Transactional
class ReservationRulesTest {
    private static final Instant CREATED = Instant.parse("2026-11-03T00:00:00Z");
    private static final Instant AFTER_LAG = CREATED.plus(Duration.ofMinutes(11));
    // 결제보다 한참 전에 갱신된 예매
    private static final Instant EARLIER = CREATED.minus(Duration.ofHours(1));
    private static final long USER = 1;
    private static final long AMOUNT = 50000;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService service;
    @Autowired
    ReconciliationRecorder recorder;
    @Autowired
    ReconciliationProperties properties;
    @Autowired
    List<ReconciliationRule> rules;
    @Autowired
    ConfigurableApplicationContext context;

    private final FakeReservationServer server = FakeReservationConfiguration.SERVER;
    private ReconciliationFixtures fixtures;

    @BeforeEach
    void setUp() {
        server.reset();
        FakeReservationConfiguration.TRANSACTION_ACTIVE_AT_CALL.clear();
        fixtures = new ReconciliationFixtures(jdbc);
        fixtures.wipe();
    }

    // ----- RC-1

    @Test
    void RC1_승인됐는데_예매가_HELD이면_잡는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.HELD, AMOUNT, null, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-1", payment.id));
        var detail = jdbc.queryForObject("SELECT detail::text FROM reconciliation_mismatches WHERE rule = 'RC-1'", String.class);
        assertTrue(detail.contains("HELD") && detail.contains("APPROVED"), "양쪽 상태를 담는다: " + detail);
        assertFalse(detail.contains("email"), "개인정보는 담지 않는다");
    }

    @Test
    void RC1_예매가_확정됐으면_정상이다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule < 'RC-7'"));
    }

    @Test
    void 허용_지연_안이면_기록하지_않고_지난_뒤에_기록한다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.HELD, AMOUNT, null, USER, EARLIER);

        service.runOnce(CREATED.plus(Duration.ofMinutes(9)));
        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule < 'RC-7'"), "허용 지연 안에서는 기록하지 않는다");

        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-1", payment.id));
    }

    @Test
    void 허용_지연은_예매의_갱신_시각도_따른다() {
        var payment = approved(AMOUNT, USER);
        // 결제는 오래됐지만 예매가 방금 바뀌었다
        reservation(payment.reservation, ReservationStatus.HELD, AMOUNT, null, USER, CREATED.plus(Duration.ofMinutes(5)));

        service.runOnce(AFTER_LAG);
        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule < 'RC-7'"));

        service.runOnce(CREATED.plus(Duration.ofMinutes(16)));
        assertEquals(1, open("RC-1", payment.id));
    }

    @Test
    void 다음_회차에_맞으면_RESOLVED로_닫는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.HELD, AMOUNT, null, USER, EARLIER);
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-1", payment.id));

        reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER, EARLIER);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));

        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM reconciliation_mismatches WHERE rule = 'RC-1' AND payment_id = ?", String.class, payment.id));
    }

    // ----- RC-2

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"EXPIRED", "RELEASED", "CANCELED"})
    void RC2_승인됐는데_예매가_끝났고_환불_행이_없으면_잡는다(ReservationStatus status) {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, status, AMOUNT, null, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-2", payment.id));
        assertEquals(0, open("RC-1", payment.id));
    }

    @Test
    void RC2_예매_서비스에_예매가_없으면_잡고_지연은_결제_쪽_시각을_쓴다() {
        var payment = approved(AMOUNT, USER);

        service.runOnce(CREATED.plus(Duration.ofMinutes(9)));
        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule < 'RC-7'"));

        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-2", payment.id));
        var detail = jdbc.queryForObject("SELECT detail::text FROM reconciliation_mismatches WHERE rule = 'RC-2'", String.class);
        assertTrue(detail.contains("\"reservation\": null") || detail.contains("\"reservation\":null"), detail);
    }

    @Test
    void RC2_환불_행이_있으면_FAILED여도_잡지_않는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.EXPIRED, AMOUNT, null, USER, EARLIER);
        fixtures.refund(payment.id, "FAILED", CREATED);

        service.runOnce(AFTER_LAG);

        assertEquals(0, open("RC-2", payment.id));
    }

    // ----- RC-3

    @Test
    void RC3_환불됐는데_예매가_이_결제로_확정이면_잡는다() {
        var payment = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(payment.id, "REFUNDED", CREATED);
        reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-3", payment.id));
    }

    @Test
    void RC3_예매가_다른_결제로_확정이거나_취소됐으면_잡지_않는다() {
        var other = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(other.id, "REFUNDED", CREATED);
        reservation(other.reservation, ReservationStatus.CONFIRMED, AMOUNT, other.id + 1000, USER, EARLIER);
        var canceled = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(canceled.id, "REFUNDED", CREATED);
        reservation(canceled.reservation, ReservationStatus.CANCELED, AMOUNT, canceled.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(0, open("RC-3", other.id) + open("RC-3", canceled.id));
    }

    // ----- RC-4

    @Test
    void RC4_확정됐는데_결제_ID가_없으면_잡는다() {
        var reservationId = fixtures.newReservation();
        reservation(reservationId, ReservationStatus.CONFIRMED, AMOUNT, null, USER, EARLIER);
        server.setChanges(List.of(server.snapshot(reservationId)));

        service.runOnce(AFTER_LAG);

        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND reservation_id = ? "
                + "AND payment_id IS NULL AND status = 'OPEN'", reservationId));
    }

    @Test
    void RC4_확정됐는데_가리키는_결제가_없거나_승인이_아니거나_다른_예매의_결제면_잡는다() {
        var missing = fixtures.newReservation();
        reservation(missing, ReservationStatus.CONFIRMED, AMOUNT, 9_999_999L, USER, EARLIER);
        var declined = payment("DECLINED", AMOUNT, USER);
        reservation(declined.reservation, ReservationStatus.CONFIRMED, AMOUNT, declined.id, USER, EARLIER);
        var owner = approved(AMOUNT, USER);
        var borrower = fixtures.newReservation();
        reservation(borrower, ReservationStatus.CONFIRMED, AMOUNT, owner.id, USER, EARLIER);
        server.setChanges(List.of(server.snapshot(missing), server.snapshot(declined.reservation), server.snapshot(borrower)));

        service.runOnce(AFTER_LAG);

        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND reservation_id = ? "
                + "AND status = 'OPEN'", missing));
        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND reservation_id = ? "
                + "AND status = 'OPEN'", declined.reservation));
        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND reservation_id = ? "
                + "AND payment_id = ? AND status = 'OPEN'", borrower, owner.id));
    }

    @Test
    void RC4_정상_확정과_환불된_결제의_확정은_잡지_않는다() {
        var approved = approved(AMOUNT, USER);
        reservation(approved.reservation, ReservationStatus.CONFIRMED, AMOUNT, approved.id, USER, EARLIER);
        var refunded = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(refunded.id, "REFUNDED", CREATED);
        reservation(refunded.reservation, ReservationStatus.CONFIRMED, AMOUNT, refunded.id, USER, EARLIER);
        server.setChanges(List.of(server.snapshot(approved.reservation), server.snapshot(refunded.reservation)));

        service.runOnce(AFTER_LAG);

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4'"),
                "REFUNDED 결제의 확정은 RC-3이 맡는다");
    }

    @Test
    void RC4_허용_지연_안이면_기록하지_않는다() {
        var reservationId = fixtures.newReservation();
        reservation(reservationId, ReservationStatus.CONFIRMED, AMOUNT, null, USER, CREATED.plus(Duration.ofMinutes(5)));
        server.setChanges(List.of(server.snapshot(reservationId)));

        service.runOnce(AFTER_LAG);

        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4'"));
    }

    @Test
    void RC4_변경_목록에서_빠져도_OPEN_행의_예매는_다시_확인해_닫는다() {
        var reservationId = fixtures.newReservation();
        reservation(reservationId, ReservationStatus.CONFIRMED, AMOUNT, null, USER, EARLIER);
        server.setChanges(List.of(server.snapshot(reservationId)));
        service.runOnce(AFTER_LAG);
        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'OPEN'"));

        // 다음 회차에는 변경 목록이 비지만, OPEN 행의 예매를 묶음 조회로 다시 보고 여전히 어긋남을 이어 간다
        server.setChanges(List.of());
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5)));
        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'OPEN'"));

        var payment = approved(AMOUNT, USER);
        jdbc.update("UPDATE payments SET reservation_id = ? WHERE id = ?", reservationId, payment.id);
        reservation(reservationId, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER, EARLIER);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(10)));

        assertEquals(1, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'RESOLVED'"));
        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'OPEN'"));
    }

    // ----- RC-5, RC-6

    @Test
    void RC5_결제_금액과_예매_금액이_다르면_잡는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT + 10000, payment.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-5", payment.id));
    }

    @Test
    void RC5_환불_금액이_PG_승인_금액과_다르면_잡는다() {
        var payment = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(payment.id, "REFUNDED", CREATED);
        jdbc.update("UPDATE refunds SET amount = 40000 WHERE payment_id = ?", payment.id);
        reservation(payment.reservation, ReservationStatus.CANCELED, AMOUNT, payment.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-5", payment.id));
    }

    @Test
    void RC5_금액이_모두_맞으면_정상이다() {
        var payment = payment("REFUNDED", AMOUNT, USER);
        fixtures.refund(payment.id, "REFUNDED", CREATED);
        reservation(payment.reservation, ReservationStatus.CANCELED, AMOUNT, payment.id, USER, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(0, open("RC-5", payment.id));
    }

    @Test
    void RC6_결제_사용자와_예매_사용자가_다르면_잡는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER + 1, EARLIER);

        service.runOnce(AFTER_LAG);

        assertEquals(1, open("RC-6", payment.id));
    }

    // ----- 예매 조회 실패

    @ParameterizedTest
    @EnumSource(Failure.class)
    void 조회가_실패하면_규칙이_실패하고_열린_불일치를_닫지_않으며_예매_없음으로_기록하지도_않는다(Failure failure) {
        var existing = approved(AMOUNT, USER);
        reservation(existing.reservation, ReservationStatus.EXPIRED, AMOUNT, null, USER, EARLIER);
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-2", existing.id));
        var lastSeenBefore = jdbc.queryForObject("SELECT last_seen_at FROM reconciliation_mismatches WHERE rule = 'RC-2'",
                Timestamp.class);
        // 이 예매가 정상으로 돌아왔어도 조회가 실패하는 회차에는 닫을 수 없다
        reservation(existing.reservation, ReservationStatus.CONFIRMED, AMOUNT, existing.id, USER, EARLIER);
        var fresh = approved(AMOUNT, USER);
        server.resetCallCounts();
        failure.apply(server, existing.reservation);

        var thrown = assertThrows(ReconciliationFailedException.class, () -> service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(5))));

        assertTrue(thrown.getSuppressed().length >= 1, "예매 규칙이 모두 실패한다");
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM reconciliation_mismatches WHERE rule = 'RC-2' "
                + "AND payment_id = ?", String.class, existing.id), "닫지 않는다");
        assertEquals(lastSeenBefore, jdbc.queryForObject("SELECT last_seen_at FROM reconciliation_mismatches WHERE rule = 'RC-2' "
                + "AND payment_id = ?", Timestamp.class, existing.id), "기록도 갱신하지 않는다");
        assertEquals(0, open("RC-2", fresh.id), "조회 실패(404 포함)를 예매 없음으로 읽지 않는다");
        assertEquals(1, server.byIdBatchSizes().size(), "실패한 뒤 규칙마다 다시 부르지 않는다");

        // 조회가 돌아오면 다음 회차에 닫는다
        server.reset();
        reservation(existing.reservation, ReservationStatus.CONFIRMED, AMOUNT, existing.id, USER, EARLIER);
        reservation(fresh.reservation, ReservationStatus.CONFIRMED, AMOUNT, fresh.id, USER, EARLIER);
        service.runOnce(AFTER_LAG.plus(Duration.ofMinutes(10)));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM reconciliation_mismatches WHERE rule = 'RC-2' "
                + "AND payment_id = ?", String.class, existing.id));
    }

    enum Failure {
        NOT_FOUND_404 {
            void apply(FakeReservationServer server, long reservationId) { server.respondWith(404); }
        },
        SERVER_ERROR_500 {
            void apply(FakeReservationServer server, long reservationId) { server.respondWith(500); }
        },
        TIMEOUT {
            void apply(FakeReservationServer server, long reservationId) { server.delayResponses(1500); }
        },
        UNKNOWN_STATUS {
            void apply(FakeReservationServer server, long reservationId) { server.corruptStatusOf(reservationId); }
        };

        abstract void apply(FakeReservationServer server, long reservationId);
    }

    // ----- 회차당 호출 수, 준비 뒤에 생긴 대상

    @Test
    void 한_회차에_묶음_조회는_대상_수_나누기_100번만_부르고_규칙마다_다시_부르지_않는다() {
        for (var i = 0; i < 250; i++) {
            var payment = approved(AMOUNT, USER);
            reservation(payment.reservation, ReservationStatus.CONFIRMED, AMOUNT, payment.id, USER, EARLIER);
        }

        service.runOnce(AFTER_LAG);

        assertEquals(List.of(100, 100, 50), server.byIdBatchSizes());
        assertEquals(1, server.changeRequests().size(), "변경 목록은 비어 있어 한 쪽이다");
        assertEquals(0, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule < 'RC-7'"));
    }

    @Test
    void 변경_목록은_커서로_끝까지_읽는다() {
        var items = new ArrayList<com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot>();
        for (var i = 0; i < 150; i++) {
            var id = fixtures.newReservation();
            reservation(id, ReservationStatus.CONFIRMED, AMOUNT, null, USER, EARLIER);
            items.add(server.snapshot(id));
        }
        server.setChanges(items);

        service.runOnce(AFTER_LAG);

        assertEquals(2, server.changeRequests().size(), "100건 + 50건 두 쪽이다");
        assertEquals(150, count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = 'RC-4' AND status = 'OPEN'"));
    }

    @Test
    void 준비_때_묻지_않은_결제는_판단을_미루고_OPEN을_닫지_않는다() {
        var payment = approved(AMOUNT, USER);
        reservation(payment.reservation, ReservationStatus.EXPIRED, AMOUNT, null, USER, EARLIER);
        service.runOnce(AFTER_LAG);
        assertEquals(1, open("RC-2", payment.id));

        // 준비 이후에 생긴 대상처럼, 스냅숏이 이 예매를 묻지 않은 상태로 판단·기록만 돌린다
        var rc2 = rules.stream().filter(rule -> rule.code() == RuleCode.RC_2).findFirst().orElseThrow();
        var withoutSnapshot = new Preparation(new ReservationView(Set.of(), Map.of(), List.of()));
        var result = recorder.reconcile(rc2, withoutSnapshot, AFTER_LAG.plus(Duration.ofMinutes(5)), properties.policy());

        assertEquals(0, result.found());
        assertEquals(0, result.resolved(), "스냅숏이 없는 대상의 OPEN을 닫지 않는다");
        assertEquals(1, result.undecided());
        assertEquals(1, open("RC-2", payment.id));
    }

    @Test
    void 예매_설정은_RestClient_Builder_같은_공용_HTTP_빈을_만들지_않는다() {
        for (var name : context.getBeanNamesForType(RestClient.Builder.class)) {
            var source = String.valueOf(context.getBeanFactory().getBeanDefinition(name).getResourceDescription())
                    + context.getBeanFactory().getBeanDefinition(name).getFactoryBeanName();
            assertFalse(source.contains("reconciliation"), name + " <- " + source);
        }
    }

    // ----- 도구

    private record Seeded(long id, long reservation) {}

    private Seeded approved(long amount, long userId) {
        return payment("APPROVED", amount, userId);
    }

    private Seeded payment(String status, long amount, long userId) {
        var reservationId = fixtures.newReservation();
        var id = fixtures.payment(reservationId, status, CREATED);
        if (amount != AMOUNT || userId != USER) {
            jdbc.update("UPDATE payments SET amount = ?, pg_approved_amount = ?, user_id = ? WHERE id = ?", amount, amount,
                    userId, id);
        }
        return new Seeded(id, reservationId);
    }

    private void reservation(long id, ReservationStatus status, long amount, Long paymentId, long userId, Instant updatedAt) {
        server.put(FakeReservationServer.reservation(id, userId, status, amount, paymentId, updatedAt));
    }

    private int open(String rule, long paymentId) {
        return count("SELECT count(*) FROM reconciliation_mismatches WHERE rule = ? AND payment_id = ? AND status = 'OPEN'",
                rule, paymentId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
