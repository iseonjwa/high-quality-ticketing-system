package com.iseonjwa.payment.payment.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 고객에게 보이는 결제 번호(VARCHAR(20)). PK 순번을 밖에 노출하지 않으려고 따로 둔다(SA 12-1).
 * 형태는 {@code PAY + yyMMdd + 영숫자 8자}다.
 */
@Component
public class PaymentNoGenerator {
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int RANDOM_LENGTH = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public PaymentNoGenerator(@Qualifier("commonAuditingClock") Clock clock) {
        this.clock = clock;
    }

    public String next() {
        var number = new StringBuilder("PAY").append(DATE.format(clock.instant()));
        for (var i = 0; i < RANDOM_LENGTH; i++) {
            number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return number.toString();
    }
}
