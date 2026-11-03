package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.FakeReservationServer;
import com.iseonjwa.payment.reconciliation.application.ReservationLookupException;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequestFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.iseonjwa.payment.reconciliation.FakeReservationServer.reservation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 예매 조회 HTTP 어댑터가 가짜 서버(contracts.md의 가정 형태)의 응답을 해석하는 방식. 200이 아닌 응답은 404도 모두 조회
 * 실패이고, 없는 ID는 200 응답에서 빠진 것만 "예매 없음"이다.
 */
class ReservationLookupHttpClientTest {
    private static final Instant AT = Instant.parse("2026-11-03T00:00:00Z");

    private FakeReservationServer server;
    private ReservationLookupHttpClient client;

    @BeforeEach
    void setUp() {
        server = new FakeReservationServer();
        client = create(server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static ReservationLookupHttpClient create(String url) {
        return ReservationLookupHttpClient.create(url, Duration.ofSeconds(1), Duration.ofMillis(300), 2, null);
    }

    @Test
    void 묶음_조회는_예매를_읽고_없는_ID는_결과에서_빠진다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        server.put(reservation(2, 11, ReservationStatus.HELD, 30000, null, AT));

        var found = client.findByIds(List.of(1L, 2L, 3L));

        assertEquals(2, found.size());
        var first = found.get(0);
        assertEquals(1, first.reservationId());
        assertEquals(10, first.userId());
        assertEquals(ReservationStatus.CONFIRMED, first.status());
        assertEquals(50000, first.totalAmount());
        assertEquals(7L, first.paymentId());
        assertEquals(AT, first.updatedAt());
        assertNull(found.get(1).paymentId(), "확정 전 예매의 paymentId는 null이다");
        assertEquals(List.of(3), server.byIdBatchSizes());
    }

    @Test
    void 변경_목록은_커서로_쪽을_나눠_읽는다() {
        server.setChanges(List.of(reservation(1, 10, ReservationStatus.CONFIRMED, 100, 1L, AT),
                reservation(2, 10, ReservationStatus.CANCELED, 100, 2L, AT),
                reservation(3, 10, ReservationStatus.CONFIRMED, 100, 3L, AT)));

        var first = client.findChanges(AT.minusSeconds(60), AT, null);
        var second = client.findChanges(AT.minusSeconds(60), AT, first.nextCursor());

        assertEquals(2, first.items().size(), "쪽 크기는 설정값(2)이다");
        assertTrue(first.nextCursor() != null);
        assertEquals(1, second.items().size());
        assertNull(second.nextCursor(), "마지막 쪽은 커서가 없다");
        assertEquals(AT.minusSeconds(60).toString(), server.changeRequests().get(0).get("from"));
    }

    @Test
    void 코드가_200이_아니면_404도_조회_실패다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        for (var status : List.of(404, 500, 503)) {
            server.respondWith(status);
            var failure = assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(1L)));
            assertTrue(failure.getMessage().contains(String.valueOf(status)), failure.getMessage());
            assertThrows(ReservationLookupException.class, () -> client.findChanges(AT, AT, null));
        }
    }

    @Test
    void 읽기_시한을_넘기면_조회_실패다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        server.delayResponses(1500);

        assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(1L)));
    }

    @Test
    void 서버에_연결하지_못하면_조회_실패다() {
        var url = server.baseUrl();
        server.close();

        assertThrows(ReservationLookupException.class, () -> create(url).findByIds(List.of(1L)));
    }

    @Test
    void 알_수_없는_status는_조회_실패다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        server.corruptStatusOf(1);

        assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(1L)));
    }

    @Test
    void 정수가_아닌_금액은_조회_실패다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        server.answerWithFractionalAmounts();

        assertThrows(ReservationLookupException.class, () -> client.findByIds(List.of(1L)));
    }

    @Test
    void 서비스_ID는_로드밸런서가_찾은_인스턴스로_바뀐다() {
        server.put(reservation(1, 10, ReservationStatus.CONFIRMED, 50000, 7L, AT));
        LoadBalancerClient balancer = new LoadBalancerClient() {
            @Override
            public ServiceInstance choose(String serviceId) {
                return choose(serviceId, null);
            }

            @Override
            public <T> ServiceInstance choose(String serviceId, org.springframework.cloud.client.loadbalancer.Request<T> request) {
                return serviceId.equals("reservation-service")
                        ? new DefaultServiceInstance("i-1", serviceId, "127.0.0.1", server.port(), false) : null;
            }

            @Override
            public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
                return execute(serviceId, choose(serviceId), request);
            }

            @Override
            public <T> T execute(String serviceId, ServiceInstance instance, LoadBalancerRequest<T> request)
                    throws IOException {
                try {
                    return request.apply(instance);
                } catch (IOException | RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IOException(exception);
                }
            }

            @Override
            public URI reconstructURI(ServiceInstance instance, URI original) {
                return URI.create("http://" + instance.getHost() + ":" + instance.getPort() + original.getRawPath()
                        + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
            }
        };
        var resolver = new LoadBalancerInterceptor(balancer, new LoadBalancerRequestFactory(balancer));
        var viaServiceId = ReservationLookupHttpClient.create("http://reservation-service", Duration.ofSeconds(1),
                Duration.ofMillis(500), 100, resolver);

        assertEquals(1, viaServiceId.findByIds(List.of(1L)).size());
    }
}
