package com.iseonjwa.reservation.lottery.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 추첨 권장 시간대(D10 운영 정책). 추첨 직후 자동결제가 몰려 일반 결제와 PG 자리를 다투므로(ADR 0039)
 * 일반 예매가 적은 새벽에 추첨하도록 권한다. 막지는 않고 설정 응답에 경고만 담는다.
 */
public final class DrawTimeWindow {
    // [가정] 저트래픽 시간대. 기준선 측정(ISE-25) 뒤 실제 시간대별 요청 수로 다시 정한다
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime START = LocalTime.of(3, 0);
    public static final LocalTime END = LocalTime.of(5, 0);

    private DrawTimeWindow() {}

    // START 이상 END 미만이면 권장 시간대다
    public static boolean contains(Instant drawAt) {
        var time = drawAt.atZone(ZONE).toLocalTime();
        return !time.isBefore(START) && time.isBefore(END);
    }
}
