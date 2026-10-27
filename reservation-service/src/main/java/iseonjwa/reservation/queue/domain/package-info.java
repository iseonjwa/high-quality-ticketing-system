/**
 * 대기열 도메인 계층: 순번·입장 판정 규칙(seq ≤ admitted), 입장 토큰 클레임, 대기열 저장소 인터페이스.
 * 다른 계층과 외부 기술(Redis)에 의존하지 않는다.
 */
package iseonjwa.reservation.queue.domain;
