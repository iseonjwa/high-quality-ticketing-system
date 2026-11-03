package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.pg.infrastructure.Resilience4jPgApprovalGate;
import com.iseonjwa.payment.support.FreezableTcpProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.iseonjwa.payment.support.PaymentTokenFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * FT-4 DB 멈춤(failure-scenarios.md 4절). DB가 느린 것이 아니라 응답 없이 멈추는 경우다. 테스트 DB 앞의 {@link FreezableTcpProxy}가
 * 연결을 닫지 않고 전달만 멈춘다. 이미 빌린 연결의 쿼리가 시한 없이 기다리지 않고 소켓 시한 안에 끊겨 PG 호출 전 구간이 Gateway
 * 시한(3초) 안에 503으로 끝나는지 확인한다. 장애 시작 지점은 {@link Ft4DbOutageScenarioTest}처럼 호출 경계의 훅으로 정한다.
 *
 * <p>Note: 소켓·문장 시한은 {@code config-repo/payment-service.yml}의 값을 그대로 읽어 이 테스트에만 적용한다. 운영 값과 같음을
 * 보장하고, 시한이 없는 다른 테스트의 데이터소스는 바꾸지 않는다.
 *
 * <p>Note: 프록시가 멈춘 동안에는 테스트의 {@code jdbc}도 같은 경로라 멈춘다. 상태 조회는 {@code thaw()} 뒤에 한다.
 */
class Ft4DbFreezeScenarioTest extends ScenarioTestBase {
    private static final Duration GATEWAY_DEADLINE = Duration.ofSeconds(3);
    private static final Pattern HOST_PORT = Pattern.compile("//([^/:?]+)(?::(\\d+))?");
    private static final FreezableTcpProxy PROXY = startProxy();
    private static final String SOCKET_TIMEOUT_KEY = "spring.datasource.hikari.data-source-properties.socketTimeout";
    private static final String OPTIONS_KEY = "spring.datasource.hikari.data-source-properties.options";

    @DynamicPropertySource
    static void throughProxyWithProductionTimeouts(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", () -> HOST_PORT.matcher(testDbUrl()).replaceFirst("//127.0.0.1:" + PROXY.port()));
        var config = new YamlPropertySourceLoader().load("payment-service", new FileSystemResource("../config-repo/payment-service.yml"))
                .get(0);
        for (var key : List.of(SOCKET_TIMEOUT_KEY, OPTIONS_KEY)) {
            var value = config.getProperty(key);
            assertNotNull(value, key + "가 config-repo/payment-service.yml에 없다");
            registry.add(key, () -> String.valueOf(value));
        }
    }

    // 허가를 얻은 직후 DB를 멈추는 훅이다. 허가는 실제 게이트가 내준 것이다
    @MockitoSpyBean
    Resilience4jPgApprovalGate approvalGateSpy;
    @Autowired
    DataSource dataSource;

    @BeforeEach
    void dbThawed() {
        PROXY.thaw();
    }

    // 하위 클래스의 @AfterEach가 먼저 실행된다. 기반의 정리(jdbc)가 돌기 전에 DB를 푼다
    @AfterEach
    void dbThawedAgain() {
        PROXY.thaw();
    }

    @AfterAll
    static void closeProxy() {
        PROXY.close();
    }

    @Test
    void e_TX1_구간에서_DB가_멈추면_Gateway_시한_안에_503이고_결제_행도_멱등_기록도_없고_허가를_돌려주며_같은_키로_다시_하면_성공한다() throws Exception {
        var reservation = newReservation();
        var key = UUID.randomUUID();
        var paymentToken = token(USER, reservation, AMOUNT).build();
        // 신규 승인의 허가를 얻은 직후 DB가 멈춘다. 이어지는 TX1이 응답 없는 DB를 만난다
        doAnswer(invocation -> {
            var permit = invocation.callRealMethod();
            PROXY.freeze();
            return permit;
        }).when(approvalGateSpy).acquire();

        var started = System.nanoTime();
        var failed = approve(reservation, key, paymentToken);
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        PROXY.thaw();

        assertEquals(503, failed.status(), String.valueOf(failed.body()));
        assertEquals("SERVICE_UNAVAILABLE", failed.body().get("code").asString());
        assertTrue(elapsed.compareTo(GATEWAY_DEADLINE) < 0, "Gateway 시한 안에 끝나야 한다: " + elapsed);
        assertEquals(0, paymentCount(reservation), "TX1이 롤백돼 결제 행이 없다");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Integer.class,
                key), "멱등 기록도 없다");
        assertEquals(0, PG.ledger().summary().approvedCount(), "PG 호출 전이다");
        var bulkhead = approvalGateSpy.bulkhead();
        assertEquals(bulkhead.getBulkheadConfig().getMaxConcurrentCalls(), bulkhead.getMetrics().getAvailableConcurrentCalls(),
                "허가를 돌려줬다");
        invariants().assertHolds();

        // DB가 풀린 뒤 같은 키·같은 요청은 새 요청처럼 성공한다
        reset(approvalGateSpy);
        var retried = approve(reservation, key, paymentToken);

        assertEquals(200, retried.status(), String.valueOf(retried.body()));
        assertEquals("APPROVED", retried.paymentStatus());
        assertEquals(1, paymentCount(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls());
        recoverUntilIdle();
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void f_PG_승인_직후_DB가_멈추면_응답은_멈추지_않고_202이며_풀린_뒤_재확인이_APPROVED로_확정하고_승인은_한_번이다() throws Exception {
        var reservation = newReservation();
        // PG가 승인을 마친 직후 DB가 멈춘다. TX2가 응답 없는 DB를 만난다
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            PROXY.freeze();
            return result;
        }).when(pg).approve(any());

        var started = System.nanoTime();
        var response = approve(reservation, UUID.randomUUID());
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        PROXY.thaw();

        assertTrue(elapsed.compareTo(Duration.ofSeconds(10)) < 0, "응답이 멈추지 않고 돌아와야 한다: " + elapsed);
        assertEquals(202, response.status(), "TX2가 실패해도 PG 결과는 정해졌으므로 202로 응답한다: " + response.body());
        assertEquals("REQUESTED", response.paymentStatus());
        assertEquals("REQUESTED", paymentStatus(reservation));
        var order = PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow();
        assertEquals("APPROVED", order.status(), "PG에는 승인이 남았다");
        assertEquals(1, order.approveCalls());

        recoverUntilIdle();

        assertEquals("APPROVED", paymentStatus(reservation));
        assertEquals(order.pgTxId(), pgTxId(reservation));
        assertEquals(1, PG.ledger().payment(pgOrderId(reservation).toString()).orElseThrow().approveCalls(),
                "재확인은 조회만 한다");
        assertEquals(List.of("PaymentApproved"), outboxEventTypes(reservation));
        invariants().assertHolds();
    }

    @Test
    void g_문장_시한이_느린_쿼리를_57014로_끊고_같은_풀의_연결은_계속_쓸_수_있다() {
        var template = new JdbcTemplate(dataSource);

        var started = System.nanoTime();
        var failure = assertThrows(DataAccessException.class, () -> template.queryForObject("SELECT pg_sleep(3)", Object.class));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        var cause = failure.getMostSpecificCause();
        assertTrue(cause instanceof SQLException, String.valueOf(cause));
        assertEquals("57014", ((SQLException) cause).getSQLState(), "서버가 문장을 취소한다(query_canceled)");
        assertTrue(elapsed.compareTo(Duration.ofMillis(1200)) >= 0 && elapsed.compareTo(Duration.ofSeconds(2)) < 0,
                "문장 시한(1.5초) 근처에서 끊긴다: " + elapsed);
        assertEquals(1, template.queryForObject("SELECT 1", Integer.class), "같은 풀에서 이어서 쓸 수 있다");
    }

    private static String testDbUrl() {
        var url = System.getenv("TEST_PAYMENT_DB_URL");
        return url == null || url.isBlank() ? "jdbc:postgresql://localhost:25434/payment_test" : url;
    }

    private static FreezableTcpProxy startProxy() {
        var matcher = HOST_PORT.matcher(testDbUrl());
        if (!matcher.find()) throw new IllegalStateException("테스트 DB URL에서 호스트를 읽지 못했다: " + testDbUrl());
        var port = matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2));
        return new FreezableTcpProxy(matcher.group(1), port);
    }
}
