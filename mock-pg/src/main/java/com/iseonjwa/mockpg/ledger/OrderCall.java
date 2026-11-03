package com.iseonjwa.mockpg.ledger;

/**
 * 주문에 대한 호출 종류. 호출 계수의 단위다(mock-pg.md 2절).
 */
public enum OrderCall {
    APPROVE,
    LOOKUP,
    CANCEL
}
