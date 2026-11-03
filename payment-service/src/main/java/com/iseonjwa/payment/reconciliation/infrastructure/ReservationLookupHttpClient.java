package com.iseonjwa.payment.reconciliation.infrastructure;

import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.application.ReservationLookupException;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 예매 서비스의 대사용 내부 조회 API를 {@link ReservationLookup} 포트로 옮기는 어댑터. 응답 모양은 contracts.md의 "대사용 예매
 * 조회" 절(ISE-75 확정 전 가정)을 따른다.
 *
 * <p>Note: 200이 아닌 응답은 404도 모두 조회 실패다. 404를 "예매 없음"으로 읽으면 경로가 틀리거나 API가 아직 없을 때 모든 결제가
 * RC-2(환불 의무 없음)로 기록된다. "예매 없음"은 200 응답의 {@code items}에서 빠진 ID만 뜻한다. 알 수 없는 {@code status}나
 * 정수가 아닌 금액처럼 읽을 수 없는 값도 실패로 둔다(값을 지어내 판단하지 않는다).
 *
 * <p>Note: RestClient를 직접 만든다. 앱의 {@code RestClient.Builder} 빈을 쓰거나 {@code @LoadBalanced}로 바꾸면 PG 어댑터 등
 * 다른 HTTP 호출까지 영향을 받는다. 인스턴스 찾기는 이 클라이언트에만 요청 인터셉터로 붙인다.
 */
public class ReservationLookupHttpClient implements ReservationLookup {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE_PATH = "/internal/reconciliation/reservations";

    private final RestClient client;
    private final int pageSize;

    ReservationLookupHttpClient(RestClient client, int pageSize) {
        this.client = client;
        this.pageSize = pageSize;
    }

    /** @param instanceResolver 서비스 ID를 인스턴스 주소로 바꾸는 인터셉터. 주소가 이미 정해져 있으면 null */
    public static ReservationLookupHttpClient create(String baseUrl, Duration connectTimeout, Duration readTimeout,
            int pageSize, ClientHttpRequestInterceptor instanceResolver) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        var builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (instanceResolver != null) builder.requestInterceptor(instanceResolver);
        return new ReservationLookupHttpClient(builder.build(), pageSize);
    }

    @Override
    public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        var body = get("findByIds", BASE_PATH + "?ids={ids}", joined);
        return snapshots("findByIds", body.path("items"));
    }

    @Override
    public ChangesPage findChanges(Instant from, Instant to, String cursor) {
        var body = cursor == null
                ? get("findChanges", BASE_PATH + "/changes?from={from}&to={to}&size={size}", from, to, pageSize)
                : get("findChanges", BASE_PATH + "/changes?from={from}&to={to}&cursor={cursor}&size={size}", from, to,
                        cursor, pageSize);
        var next = body.path("nextCursor");
        return new ChangesPage(snapshots("findChanges", body.path("items")),
                next.isNull() || next.isMissingNode() ? null : next.asString());
    }

    private JsonNode get(String operation, String uri, Object... variables) {
        try {
            return client.get().uri(uri, variables).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                var status = response.getStatusCode().value();
                if (status != 200) throw new ReservationLookupException(operation + " 응답 코드 " + status);
                return JSON.readTree(response.getBody());
            });
        } catch (ReservationLookupException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 연결 실패, 시한 초과, 본문을 읽지 못함
            throw new ReservationLookupException(operation + " 호출 실패: " + exception.getClass().getSimpleName(), exception);
        }
    }

    private static List<ReservationSnapshot> snapshots(String operation, JsonNode items) {
        if (!items.isArray()) throw new ReservationLookupException(operation + " 응답에 items 배열이 없다");
        var result = new ArrayList<ReservationSnapshot>(items.size());
        for (var item : items) result.add(snapshot(operation, item));
        return result;
    }

    private static ReservationSnapshot snapshot(String operation, JsonNode item) {
        try {
            return new ReservationSnapshot(
                    integer(item, "reservationId"), integer(item, "userId"),
                    ReservationStatus.valueOf(text(item, "status")), integer(item, "totalAmount"),
                    item.path("paymentId").isNull() || item.path("paymentId").isMissingNode() ? null
                            : integer(item, "paymentId"),
                    optionalInstant(item, "holdExpiresAt"), instant(item, "updatedAt"));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            // 알 수 없는 status, 읽을 수 없는 시각·숫자
            throw new ReservationLookupException(operation + " 응답 항목을 읽을 수 없다: " + exception.getMessage(), exception);
        }
    }

    private static long integer(JsonNode item, String field) {
        var node = item.path(field);
        if (!node.isIntegralNumber()) throw new IllegalArgumentException(field + "가 정수가 아니다");
        return node.asLong();
    }

    private static String text(JsonNode item, String field) {
        var node = item.path(field);
        if (!node.isString()) throw new IllegalArgumentException(field + "가 없다");
        return node.asString();
    }

    private static Instant instant(JsonNode item, String field) {
        return OffsetDateTime.parse(text(item, field)).toInstant();
    }

    private static Instant optionalInstant(JsonNode item, String field) {
        var node = item.path(field);
        return node.isNull() || node.isMissingNode() ? null : instant(item, field);
    }
}
