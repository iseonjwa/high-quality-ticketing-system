package com.iseonjwa.payment.payment.domain;

/**
 * 결제를 처리한 PG. 결제마다 하나로 정해져 이후 승인·재확인·환불·Ops 조회가 모두 이 PG로 간다(ADR 0023 2026-11-10 개정).
 */
public enum PgProvider { MOCK, TOSS }
