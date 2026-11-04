package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.FakeReservationServer;
import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.application.ReservationLookupException;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static com.iseonjwa.payment.reconciliation.FakeReservationServer.reservation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code load-balanced} 설정이 운영 설정 클래스에서 조회 어댑터에 LoadBalancer 인터셉터를 붙이거나 떼는지 확인한다. 어댑터를
 * 직접 만들지 않고 설정 클래스를 컨텍스트에 올려 속성으로 고른다. LoadBalancerClient는 쓰이면 바로 실패하는 대역이다.
 */
class ReservationLoadBalancingConfigurationTest {
    private FakeReservationServer server;
    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp() {
        server = new FakeReservationServer();
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, Instant.parse("2026-11-03T00:00:00Z")));
        var balancer = Mockito.mock(LoadBalancerClient.class, invocation -> {
            throw new IllegalStateException("로드밸런서를 쓰면 안 된다: " + invocation.getMethod().getName());
        });
        runner = new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class))
                .withBean(LoadBalancerClient.class, () -> balancer)
                .withUserConfiguration(ReservationReconciliationConfiguration.class)
                .withPropertyValues("payment.reconciliation.reservation.enabled=true",
                        "payment.reconciliation.reservation.base-url=" + server.baseUrl(),
                        "payment.reconciliation.reservation.read-timeout=500ms");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void load_balanced가_false면_인터셉터_없이_base_url로_바로_부른다() {
        runner.withPropertyValues("payment.reconciliation.reservation.load-balanced=false").run(context -> {
            var found = context.getBean(ReservationLookup.class).findByIds(List.of(1L));

            assertEquals(1, found.size());
        });
    }

    @Test
    void 기본값은_load_balanced라_로드밸런서를_거친다() {
        runner.run(context -> assertThrows(ReservationLookupException.class,
                () -> context.getBean(ReservationLookup.class).findByIds(List.of(1L))));
    }
}
