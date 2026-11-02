package com.iseonjwa.reservation.lottery.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LotteryEntryRepository {

    /**
     * 응모 기간 안이고 1인 최대 매수 이하일 때만 응모 행을 넣는다. 기간은 DB 시각으로 판정한다(details.md 2절).
     * 같은 사용자·같은 추첨이나 같은 멱등키가 이미 있으면 넣지 않는다.
     *
     * @return 넣은 응모 ID. 조건이 맞지 않거나 이미 있으면 비어 있다. 이유는 호출한 쪽이 다시 읽어 가린다
     */
    Optional<Long> insertIfOpen(Long lotteryId, UUID requestId, Long userId, int quantity);

    /**
     * 응모 기간 안의 ENTERED 응모만 CANCELED로 바꾼다(D5).
     *
     * @return 바꾼 행이 있으면 true
     */
    boolean cancelIfOpen(Long lotteryId, Long userId);

    Optional<LotteryEntry> findById(Long id);

    Optional<LotteryEntry> findByRequestId(UUID requestId);

    Optional<LotteryEntry> findByLotteryIdAndUserId(Long lotteryId, Long userId);

    // 추첨 대상. 취소한 응모는 빠진다(D5). 추첨 스케줄러(ISE-87)가 순위 계산에 넘긴다
    List<DrawEntrant> findDrawEntrants(Long lotteryId);

    // 추첨 결과 중 당첨·예비만 순위와 함께 배치로 저장한다(D8 쓰기 줄이기)
    void saveDrawResults(List<DrawResult> results);

    // 남은 ENTERED를 한 문장으로 LOST로 바꾼다. 낙첨에는 순위를 저장하지 않는다(11/2 결정)
    int markRemainingLost(Long lotteryId);

    /**
     * @param drawRank 전체 응모 중 순위(1부터). 예비자는 이 값의 오름차순으로 승계한다
     */
    record DrawResult(long entryId, LotteryEntryStatus status, int drawRank) {}
}
