package com.iseonjwa.reservation.lottery.domain;

/**
 * 추첨 계산에 넘기는 응모 한 건. 엔티티 대신 계산에 필요한 값만 담는다.
 * 추첨 스케줄러·승계 워커가 {@code lottery_entries}에서 읽은 값을 이 형태로 바꿔 넘긴다.
 *
 * @param entryId  응모 ID. 순위 해시의 입력이므로 저장 뒤 바뀌지 않는 값이어야 한다
 * @param quantity 응모 매수. 1~2매다(SA 4-3, D7 10/30 유지 결정)
 */
public record DrawEntrant(long entryId, int quantity) {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 2;

    // Note: 매수는 응모 API에서 이미 검사하므로 여기 도달한 잘못된 값은 사용자 입력이 아니라 데이터·코드 오류다.
    // 그래서 업무 거절(BusinessException)이 아니라 IllegalArgumentException으로 계산을 멈춘다.
    public DrawEntrant {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("응모 매수는 1~2매다: " + quantity);
        }
    }
}
