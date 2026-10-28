package com.iseonjwa.reservation.reservation.domain.vo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

// NUMERIC(15,0) 컬럼과 Money를 바꾼다. 엔티티 필드에 @Convert로 명시해 쓴다.
@Converter
public class MoneyConverter implements AttributeConverter<Money, BigDecimal> {
    @Override
    public BigDecimal convertToDatabaseColumn(Money money) {
        return money == null ? null : money.toBigDecimal();
    }

    @Override
    public Money convertToEntityAttribute(BigDecimal amount) {
        return amount == null ? null : Money.of(amount);
    }
}
