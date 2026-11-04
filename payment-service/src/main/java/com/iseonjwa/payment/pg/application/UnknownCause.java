package com.iseonjwa.payment.pg.application;

/**
 * PG 결과를 알 수 없는 원인. 재확인 스케줄러와 지표가 원인별로 다르게 다룬다.
 */
public enum UnknownCause {
    // 응답 시간 안에 결과를 받지 못함
    TIMEOUT,
    // 연결이 끊기거나 응답을 받기 전에 닫힘
    CONNECTION_LOST,
    // PG 5xx 또는 해석할 수 없는 응답(금액·통화 누락 포함)
    PG_ERROR,
    // PG가 아직 처리 중이라고 응답함
    PROCESSING,
    // 요청 형식·인증을 거절한 4xx. 우리 쪽 설정·코드 문제라 거절이 아니고 알림 대상이다
    CLIENT_ERROR
}
