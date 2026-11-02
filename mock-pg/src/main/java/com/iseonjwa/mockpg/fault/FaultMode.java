package com.iseonjwa.mockpg.fault;

/**
 * 주입할 장애의 종류(mock-pg.md 5절의 모드와 적용 표). 스파이크는 응답 유실만 다룬다.
 */
public enum FaultMode {
    NONE,
    // 결과를 저장한 뒤 응답을 한 바이트도 보내지 않고 연결을 닫는다
    DROP_RESPONSE
}
