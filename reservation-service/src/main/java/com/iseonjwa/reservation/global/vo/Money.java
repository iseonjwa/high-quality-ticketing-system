package com.iseonjwa.reservation.global.vo;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 원 단위 금액(SA 12-1). 소수 자리는 0으로 고정하고 음수를 허용하지 않는다.
 *
 * <p>Note: {@link BigDecimal#equals}는 scale까지 비교해 {@code 1000}과 {@code 1000.0}을 다르게 본다.
 * 생성 시 scale을 0으로 맞추고, 크기 비교는 {@link #isGreaterThan}처럼 {@code compareTo}로 한다.
 */
public final class Money {
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(long amount) {
        return of(BigDecimal.valueOf(amount));
    }

    // Note: setScale(0)에 RoundingMode를 주지 않아 소수 자리가 있으면 ArithmeticException이 난다.
    // 원 단위 금액에 소수가 들어오면 반올림하지 않고 잘못된 입력으로 거절한다.
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "금액은 null일 수 없습니다");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + amount);
        }
        return new Money(amount.setScale(0));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public boolean isGreaterThan(Money other) {
        return amount.compareTo(other.amount) > 0;
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
