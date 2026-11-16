package com.iseonjwa.payment.payment.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.payment.payment.domain.PgProvider;

import java.math.BigDecimal;

/**
 * 결제 준비 응답 {@code data}. {@code mockBypass}는 토스 모드에서 결제창 없이 Mock으로 승인하는 "건너뛰기"를 받을 때만 true다. 토스에서만 {@code orderId}와 {@code clientKey}가 있다.
 * {@code fallbackProvider}는 토스가 결제를 시작하지 못할 때 넘길 PG다(토스 모드에서 폴백이 켜져 있으면 MOCK, 아니면 없다).
 * {@code primaryAvailable}은 서버 기본 PG의 승인 차단기가 닫혀 있거나 시험 중인지다(OPEN이면 false).
 * 클라이언트 키는 결제창을 여는 공개 값이다(시크릿 키는 이 응답에 절대 들어가지 않는다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutView(PgProvider provider, boolean mockBypass, String orderId, String clientKey, BigDecimal amount,
        String orderName, PgProvider fallbackProvider, boolean primaryAvailable) {}
