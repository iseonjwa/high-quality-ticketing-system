package com.iseonjwa.payment.payment.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.payment.payment.domain.PgProvider;

import java.math.BigDecimal;

/**
 * 결제 준비 응답 {@code data}. {@code mockBypass}는 토스 모드에서 결제창 없이 Mock으로 승인하는 "건너뛰기"를 받을 때만 true다. 토스에서만 {@code orderId}와 {@code clientKey}가 있다.
 * 클라이언트 키는 결제창을 여는 공개 값이다(시크릿 키는 이 응답에 절대 들어가지 않는다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutView(PgProvider provider, boolean mockBypass, String orderId, String clientKey, BigDecimal amount,
        String orderName) {}
