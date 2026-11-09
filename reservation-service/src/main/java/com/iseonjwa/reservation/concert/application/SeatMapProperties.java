package com.iseonjwa.reservation.concert.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 좌석 조회 경량화(P4) 단계 선택. 측정 브랜치 전용이다(experiment/ISE-79-p4-measure).
 * 단계는 앞 단계의 변경을 모두 포함한다. 한 이미지로 환경변수만 바꿔 단계마다 같은 빌드를 잰다.
 *
 * @param mode     RESERVATION_SEATMAP_MODE
 * @param stateTtl RESERVATION_SEATMAP_STATETTL. STATE_CACHE에서 좌석 상태를 다시 읽기까지의 시간. 0이면 캐시하지 않는다
 */
@ConfigurationProperties(prefix = "reservation.seat-map")
public record SeatMapProperties(@DefaultValue("ENTITY") Mode mode,
                                @DefaultValue("1s") Duration stateTtl) {

    public enum Mode {
        // P0'': 기존 코드. 15,000석을 엔티티로 읽는다
        ENTITY,
        // P4a: 응답에 필요한 열만 행으로 읽는다
        DTO,
        // P4b: P4a + 바뀌지 않는 배치(구역·열·번호·등급·가격)를 회차마다 한 번 읽어 둔다. 상태는 요청마다 읽는다
        LAYOUT_CACHE,
        // P4c: P4b + 좌석 상태를 stateTtl 동안 함께 쓴다
        STATE_CACHE
    }
}
