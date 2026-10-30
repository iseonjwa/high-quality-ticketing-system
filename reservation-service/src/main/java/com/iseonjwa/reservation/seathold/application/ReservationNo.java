package com.iseonjwa.reservation.seathold.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * 고객에게 보이는 예매 번호(VARCHAR(20)). 형태는 {@code R + yyyyMMdd(KST) + "-" + 영숫자 8자}다(18자).
 *
 * <p>Note: 예매 PK는 IDENTITY라 INSERT 전에는 알 수 없고, PK 순번을 밖에 드러내지 않으려고(SA 12-1) 난수로 만든다.
 * 혼동하기 쉬운 I·O는 뺐다. 같은 날 충돌 확률은 34^8분의 1 수준이고, 나면 UNIQUE 제약이 막는다.
 */
public final class ReservationNo {
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int RANDOM_LENGTH = 8;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Seoul"));

    private ReservationNo() {}

    public static String next(Instant now, RandomGenerator random) {
        var number = new StringBuilder("R").append(DATE.format(now)).append('-');
        for (var i = 0; i < RANDOM_LENGTH; i++) {
            number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return number.toString();
    }
}
