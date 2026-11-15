package com.iseonjwa.reservation.seathold.observability;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.seathold.application.SeatHoldErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 좌석 선점 API 응답을 결과별로 센다(ISE-277). 409는 판매 전·매수 초과·좌석 충돌·결제 불가가 모두 같은 상태 코드라
 * HTTP 지표만으로는 좌석 충돌을 따로 볼 수 없다. 라벨에는 사용자·좌석·회차 ID를 넣지 않는다.
 *
 * <p>Note: 선점 API까지 들어온 요청만 센다. 본문 검증 실패처럼 컨트롤러 메서드 전에 끝난 400은 HTTP 지표로 본다.
 * 같은 멱등키 재요청도 응답 하나로 센다. 접수형 소비자의 결과(HELD·REJECTED)는 여기서 세지 않는다.
 */
@Component
public class SeatHoldMetrics {
    static final String NAME = "seathold.result";

    public enum Outcome {
        // 동기형 201
        HELD("held"),
        // 접수형 202. 동기형 서버가 접수형으로 시작한 요청에 답한 202도 여기다(ADR 0058)
        ACCEPTED("accepted"),
        SEAT_CONFLICT("seat_conflict"),
        // 자동 배정에서 고른 구역·등급에 남은 좌석이 모자람(ISE-264). 좌석 충돌과 나눠 본다
        ZONE_SOLD_OUT("zone_sold_out"),
        LIMIT_EXCEEDED("limit_exceeded"),
        NOT_OPEN("not_open"),
        NOT_PAYABLE("not_payable"),
        ENTRY_TOKEN_USED("entry_token_used"),
        // 그 밖의 업무 거절(좌석 수 불일치, 회차 없음, 멱등키 재사용 등)
        INVALID("invalid"),
        // 업무 거절이 아닌 예외(500)
        ERROR("error");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    static final String PRE_REJECTED_NAME = "seathold.pre_rejected";

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Counter preRejected;

    // 기동할 때 모든 결과를 0으로 등록해 둔다. 요청마다 등록하지 않고, 아직 없는 결과도 rate()가 0으로 보인다
    public SeatHoldMetrics(MeterRegistry registry) {
        for (var outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(NAME)
                    .description("좌석 선점 API 응답 수(결과별)")
                    .tag("outcome", outcome.label)
                    .register(registry));
        }
        // Note: outcome 값을 늘리지 않고 따로 센다. 사전 거절도 응답은 같은 409라 seathold.result에는 seat_conflict로 들어가고,
        // 이 카운터는 그중 트랜잭션 전에 끝난 수다. seat_conflict를 둘로 나누면 기존 대시보드의 좌석 충돌 비율이 바뀐다(ISE-281)
        preRejected = Counter.builder(PRE_REJECTED_NAME)
                .description("좌석 조회 1초 캐시로 트랜잭션 전에 거절한 선점 수(seat_conflict의 일부)")
                .register(registry);
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public void recordPreRejected() {
        preRejected.increment();
    }

    public void recordFailure(RuntimeException exception) {
        record(outcomeOf(exception));
    }

    static Outcome outcomeOf(RuntimeException exception) {
        if (!(exception instanceof BusinessException business)) {
            return Outcome.ERROR;
        }
        if (!(business.getErrorCode() instanceof SeatHoldErrorCode code)) {
            return Outcome.INVALID;
        }
        return switch (code) {
            case SEAT_ALREADY_HELD -> Outcome.SEAT_CONFLICT;
            case ZONE_SOLD_OUT -> Outcome.ZONE_SOLD_OUT;
            case PURCHASE_LIMIT_EXCEEDED -> Outcome.LIMIT_EXCEEDED;
            case SALE_NOT_OPEN -> Outcome.NOT_OPEN;
            case RESERVATION_NOT_PAYABLE -> Outcome.NOT_PAYABLE;
            case ENTRY_TOKEN_USED -> Outcome.ENTRY_TOKEN_USED;
            case SEAT_COUNT_MISMATCH -> Outcome.INVALID;
        };
    }
}
