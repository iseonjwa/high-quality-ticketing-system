package com.iseonjwa.verification;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.iseonjwa.verification.WorkspaceClient.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실행 중인 local 스택에서 Gateway를 거쳐 일반 예매 한 흐름(공연 준비 → 대기열 → 선점 → 결제 → 확정 → 취소 → 환불 완료)을 확인한다.
 * 실행할 때마다 회차가 하나 생기고 회차마다 좌석이 15,000석 만들어지므로 local DB가 점점 커진다.
 */
@Tag("system")
class BookingFlowSystemTest {
    /** 공연장 목록 API가 없어 시드 데이터의 첫 공연장 ID에 기댄다. 시드가 바뀌면 이 값도 바꾼다. */
    private static final long SEEDED_VENUE_ID = 1;
    private static final Duration SEAT_CREATION_TIMEOUT = Duration.ofSeconds(60);

    private static WorkspaceClient client;

    @BeforeAll
    static void connect() throws Exception {
        client = new WorkspaceClient();
    }

    @AfterAll
    static void close() {
        if (client != null) client.close();
    }

    @Test
    void 일반_예매가_대기열_선점_결제_확정_취소_환불까지_이어진다() throws Exception {
        // 1. 관리자가 공연·회차·좌석을 준비한다.
        var admin = client.login(client.env("ADMIN_EMAIL"), client.env("ADMIN_PASSWORD")).get("accessToken").asString();
        var title = "E2E 예매 공연 " + UUID.randomUUID().toString().substring(0, 8);
        var concertId = client.check("공연 등록", "POST", "/api/v1/admin/concerts", 201,
                Map.of("title", title, "description", "시스템 E2E"), admin).get("data").get("concertId").asLong();
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var scheduleId = client.check("회차 등록", "POST", "/api/v1/admin/concerts/" + concertId + "/schedules", 201,
                Map.of("venueId", SEEDED_VENUE_ID, "startsAt", now.plus(7, ChronoUnit.DAYS).toString(),
                        "openAt", now.minus(1, ChronoUnit.MINUTES).toString(), "maxPerUser", 4, "holdMinutes", 5),
                admin).get("data").get("scheduleId").asLong();
        client.check("좌석 생성", "POST", "/api/v1/admin/schedules/" + scheduleId + "/seats", 201,
                Map.of("prices", List.of(Map.of("grade", "VIP", "price", 165000),
                        Map.of("grade", "R", "price", 143000), Map.of("grade", "S", "price", 112000))),
                admin, Map.of(), SEAT_CREATION_TIMEOUT);

        // 2. 사용자가 가입하고, 방금 만든 공연·회차가 목록·상세에 보인다.
        var user = client.account();
        var list = client.check("공연 목록", "GET", "/api/v1/concerts?page=0&size=100", 200, null, null).get("data");
        assertTrue(anyMatch(list.get("content"), "concertId", concertId),
                "공연 목록에 방금 만든 공연이 보이지 않는다: concertId=" + concertId);
        var detail = client.check("공연 상세", "GET", "/api/v1/concerts/" + concertId, 200, null, null).get("data");
        assertTrue(anyMatch(detail.get("schedules"), "scheduleId", scheduleId),
                "공연 상세에 방금 만든 회차가 보이지 않는다: scheduleId=" + scheduleId);

        // 3. 대기열에 들어가 READY가 될 때까지 기다린다.
        var queue = client.request("POST", "/api/v1/schedules/" + scheduleId + "/queue", null, user.access());
        assertTrue(queue.status() == 200 || queue.status() == 201, "대기열 진입: " + queue.status());
        await("대기열 READY", () -> {
            var me = client.request("GET", "/api/v1/schedules/" + scheduleId + "/queue/me", null, user.access());
            return me.status() == 200 && "READY".equals(me.body().get("data").get("status").asString());
        }, Duration.ofSeconds(60));

        // 4. 입장 토큰: 발급 API가 아직 없어 ENTRY_TOKEN_SECRET으로 직접 서명한다.
        // Note: ISE-69가 나오면 POST /schedules/{id}/queue/token으로 받는 방식으로 바꾼다.
        var entry = Map.of("X-Entry-Token", client.entryToken(user.id(), scheduleId, client.env("ENTRY_TOKEN_SECRET")));

        // 5. 좌석 조회 → S석 빈 좌석 선점
        var seats = client.check("좌석 조회", "GET", "/api/v1/schedules/" + scheduleId + "/seats", 200, null,
                user.access(), entry, Duration.ofSeconds(30)).get("data");
        var seatId = firstAvailableSeat(seats, "S");
        var holdKey = UUID.randomUUID().toString();
        var holdHeaders = Map.of("X-Entry-Token", entry.get("X-Entry-Token"), "Idempotency-Key", holdKey);
        var hold = client.check("좌석 선점", "POST", "/api/v1/schedules/" + scheduleId + "/reservations", 201,
                Map.of("seatIds", List.of(seatId)), user.access(), holdHeaders).get("data");
        assertEquals("HELD", hold.get("status").asString(), "선점 상태");
        assertNotNull(hold.get("paymentToken"), "선점 응답의 paymentToken");
        assertEquals(112000, hold.get("totalAmount").asInt(), "S석 선점 금액");

        // 6. 결제 승인과 조회
        var payment = client.check("결제 승인", "POST", "/api/v1/payments", 200,
                Map.of("paymentToken", hold.get("paymentToken").asString(), "amount", hold.get("totalAmount").asInt(),
                        "method", "CARD"), user.access(), Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                .get("data");
        assertEquals("APPROVED", payment.get("status").asString(), "결제 승인 상태");
        var paymentId = payment.get("paymentId").asLong();
        var fetched = client.check("결제 조회", "GET", "/api/v1/payments/" + paymentId, 200, null, user.access())
                .get("data");
        assertEquals("APPROVED", fetched.get("status").asString(), "결제 조회 상태");

        // 7. 예매 확정: 같은 멱등키와 좌석으로 선점을 다시 보내 CONFIRMED 응답을 기다린다.
        // Note: 예매 조회 API(ISE-171)가 나오면 예매 상세 조회로 바꾼다.
        var last = new AtomicReference<String>("응답 없음");
        var confirmedReservationId = new AtomicLong();
        try {
            await("예매 확정", () -> {
                WorkspaceClient.Response again;
                try {
                    again = client.request("POST", WorkspaceClient.BASE + "/api/v1/schedules/" + scheduleId
                            + "/reservations", Map.of("seatIds", List.of(seatId)), user.access(), holdHeaders);
                } catch (Exception e) {
                    last.set(e.toString());
                    throw e;
                }
                last.set("HTTP " + again.status() + ", body=" + again.body());
                var data = again.body() == null ? null : again.body().get("data");
                var status = data == null || data.get("status") == null ? "-" : data.get("status").asString();
                var confirmed = (again.status() == 200 || again.status() == 201) && "CONFIRMED".equals(status);
                if (confirmed) confirmedReservationId.set(data.get("reservationId").asLong());
                return confirmed;
            }, Duration.ofSeconds(30));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " (마지막 응답: " + last.get() + ")", e);
        }
        var reservationId = confirmedReservationId.get();
        assertEquals(hold.get("reservationId").asLong(), reservationId, "확정 응답의 예매 ID");

        // 8. 예매를 취소한다. 202는 접수이고 환불은 결제 서비스가 비동기로 끝낸다.
        var cancel = client.check("예매 취소", "POST", "/api/v1/reservations/" + reservationId + "/cancel", 202, null,
                user.access(), Map.of("Idempotency-Key", UUID.randomUUID().toString())).get("data");
        assertEquals("CANCELED", cancel.get("status").asString(), "취소 응답 상태");
        assertEquals(paymentId, cancel.get("paymentId").asLong(), "취소 응답의 결제 ID");

        // 9. 결제가 REFUNDED가 되고 환불 시각이 채워질 때까지 기다린다.
        var refunded = new AtomicReference<JsonNode>();
        await("환불 완료", () -> {
            var response = client.request("GET", "/api/v1/payments/" + paymentId, null, user.access());
            if (response.status() != 200) return false;
            var data = response.body().get("data");
            refunded.set(data);
            return "REFUNDED".equals(data.get("status").asString());
        }, Duration.ofSeconds(60));
        var refundedAt = refunded.get().get("refundedAt");
        assertTrue(refundedAt != null && !refundedAt.isNull(), "환불 시각(refundedAt)이 비어 있다: " + refunded.get());

        // 10. 예매 상세는 CANCELED이고 티켓이 없다.
        var canceled = client.check("취소한 예매 조회", "GET", "/api/v1/reservations/" + reservationId, 200, null,
                user.access()).get("data");
        assertEquals("CANCELED", canceled.get("status").asString(), "취소한 예매 상태");
        assertEquals(0, canceled.get("tickets").size(), "취소한 예매의 티켓");
        assertEquals(paymentId, canceled.get("paymentId").asLong(), "예매 상세의 결제 ID");

        // 11. 취소한 좌석이 다시 빈 좌석으로 보인다.
        var seatsAfter = client.check("취소 뒤 좌석 조회", "GET", "/api/v1/schedules/" + scheduleId + "/seats", 200, null,
                user.access(), entry, Duration.ofSeconds(30)).get("data");
        assertTrue(isAvailable(seatsAfter, seatId), "취소한 좌석이 다시 선택 가능해야 한다: seatId=" + seatId);
    }

    private static boolean isAvailable(JsonNode seats, long seatId) {
        for (var section : seats.get("sections")) {
            for (var seat : section.get("seats")) {
                if (seat.get("seatId").asLong() == seatId) return seat.get("available").asBoolean();
            }
        }
        throw new AssertionError("좌석 조회에 좌석이 없다: seatId=" + seatId);
    }

    private static boolean anyMatch(JsonNode array, String field, long expected) {
        for (var item : array) {
            if (item.get(field).asLong() == expected) return true;
        }
        return false;
    }

    private static long firstAvailableSeat(JsonNode seats, String grade) {
        for (var section : seats.get("sections")) {
            if (!grade.equals(section.get("grade").asString())) continue;
            for (var seat : section.get("seats")) {
                if (seat.get("available").asBoolean()) return seat.get("seatId").asLong();
            }
        }
        throw new AssertionError("좌석 조회: " + grade + "석에 빈 좌석이 없다");
    }
}
