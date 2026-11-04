package com.iseonjwa.reservation.reservation.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * 고객에게 보이는 티켓 번호(VARCHAR(20)). 형태는 {@code T + yyyyMMdd(KST) + "-" + 영숫자 8자}다(18자).
 * 예매 번호(seathold의 ReservationNo)와 같은 규칙이고 앞 글자만 다르다. 충돌하면 UNIQUE 제약이 막는다.
 */
final class TicketNo {
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int RANDOM_LENGTH = 8;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Seoul"));

    private TicketNo() {}

    static String next(Instant now, RandomGenerator random) {
        var number = new StringBuilder("T").append(DATE.format(now)).append('-');
        for (var i = 0; i < RANDOM_LENGTH; i++) {
            number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return number.toString();
    }
}
