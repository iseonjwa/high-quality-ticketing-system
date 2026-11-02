package com.iseonjwa.mockpg.fault;

/**
 * 장애를 주입할 호출 종류. 호출 종류마다 설정을 따로 가진다(mock-pg.md 5절).
 * 스파이크는 승인만 다룬다. 조회·망취소·환불은 PR 1·2에서 더한다.
 */
public enum FaultTarget {
    APPROVE
}
