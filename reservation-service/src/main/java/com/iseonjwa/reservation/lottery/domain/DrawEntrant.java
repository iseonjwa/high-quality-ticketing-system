package com.iseonjwa.reservation.lottery.domain;

/**
 * 추첨 계산에 넘기는 응모 한 건으로, 엔티티 대신 계산에 필요한 값만 담는다.
 *
 * @param entryId  순위 해시의 입력이므로 저장 뒤 바뀌지 않아야 하는 응모 ID
 * @param quantity 응모 매수(1~2매, SA 4-3, D7 10/30 유지 결정)
 */
public record DrawEntrant(long entryId, int quantity) {

    public static final int MIN_QUANTITY = Lottery.MIN_PER_ENTRY;
    public static final int MAX_QUANTITY = Lottery.MAX_PER_ENTRY;

    // Note: 응모 API가 이미 검사한 값이라 잘못된 값은 데이터·코드 오류로 보고 IllegalArgumentException으로 계산을 멈춘다.
    public DrawEntrant {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("응모 매수는 1~2매다: " + quantity);
        }
    }
}
