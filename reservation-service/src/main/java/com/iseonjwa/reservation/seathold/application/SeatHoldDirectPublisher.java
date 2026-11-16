package com.iseonjwa.reservation.seathold.application;

/**
 * 접수형 선점 요청을 Outbox를 거치지 않고 커밋 뒤 Kafka로 바로 보내는 포트(측정 전용, ISE-18).
 * 기본 경로는 Outbox다({@code seat-hold.async.publish=outbox}). SA 10-1 비고의 "동기형·직접 발행·Outbox 세 경우 비교"에서
 * Outbox 폴링 지연을 뺀 경로로만 쓴다.
 *
 * <p>Note: 예외를 던지지 않는다. 호출 시점에는 요청 행이 이미 커밋돼 있어, 같은 키 재요청도 202 재응답이 된다. 그래서 전송이
 * 실패해도 사용자에게는 202로 답하고 실패는 지표로 센다. 메시지가 가지 않은 요청은 30초 시한 정리가 TIMEOUT으로 닫는다(ISE-17)
 */
public interface SeatHoldDirectPublisher {

    /** Outbox 행과 같은 봉투·키로 보낸다. 브로커 확인을 기다리고, 실패하면 지표만 남긴다. */
    void publish(String topic, String messageKey, String eventType, int version, Object payload);
}
