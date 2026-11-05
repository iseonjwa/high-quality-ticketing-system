package com.iseonjwa.reservation.lottery.domain.policy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 자동결제가 일반 결제와 PG 자리를 다투지 않게 새벽 추첨을 권하는 시간대로, 막지 않고 설정 응답에 경고만 담는다(D10, ADR 0039).
 */
public final class DrawTimeWindow {
    // [가정] 저트래픽 시간대이며, 기준선 측정(ISE-25) 뒤 실제 시간대별 요청 수로 다시 정한다
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime START = LocalTime.of(3, 0);
    public static final LocalTime END = LocalTime.of(5, 0);

    private DrawTimeWindow() {}

    // START 이상 END 미만이면 권장 시간대다
    public static boolean contains(Instant drawAt) {
        LocalTime time = drawAt.atZone(ZONE).toLocalTime();
        return !time.isBefore(START) && time.isBefore(END);
    }
}
