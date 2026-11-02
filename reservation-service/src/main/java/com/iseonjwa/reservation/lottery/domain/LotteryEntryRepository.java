package com.iseonjwa.reservation.lottery.domain;

import java.time.Instant;
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

    /**
     * 아직 예매가 없는 당첨 응모 하나를 순위 순으로 집어 행 잠금을 건다. 다른 워커가 잡은 행은 건너뛴다(SKIP LOCKED, D10).
     * 부르는 쪽 트랜잭션이 끝날 때까지 잠금이 유지된다.
     */
    Optional<UnassignedWinner> lockNextUnassignedWinner();

    // 당첨 응모에 예매를 연결하고 결제 시도 횟수를 1 올린다. WON이고 아직 예매가 없을 때만 바꾼다
    boolean linkReservation(Long entryId, Long reservationId);

    // 예매를 만들 수 없는 당첨(매수 한도 초과, 남은 자리 없음)을 FAILED로 바꾼다. WON이고 예매가 없을 때만
    boolean markAssignmentFailed(Long entryId, String reason);

    // 결제 확정. 이 예매에 연결된 WON 응모만 PAID로 바꾼다
    boolean markPaid(Long reservationId);

    // 결제 실패·만료. 이 예매에 연결된 WON 응모만 FAILED로 바꾼다
    boolean markFailed(Long reservationId, String reason);

    // 예매에 연결된 WON 응모가 있으면 그 추첨의 추첨 시각. 선점 만료가 추첨 예매인지 가리고 연장 상한을 계산할 때 쓴다
    Optional<Instant> findWonDrawAtByReservationId(Long reservationId);

    /**
     * 우리 책임 실패(NOT_SENT) 재시도(D12). 시도가 {@code maxAttempts}보다 적고 승계 마감 전이면 예매 연결만 비운다.
     * 그러면 배정 워커가 새 예매로 다시 시도한다(WON → WON).
     *
     * @return 재시도로 돌렸으면 true. 횟수나 시각 조건을 넘었으면 false
     */
    boolean releaseForRetry(Long reservationId, int maxAttempts);

    record UnassignedWinner(long entryId, long lotteryId, long scheduleId, long userId, int quantity) {}

    // 당첨(WON)·결제 완료(PAID) 매수 합. 승계할 남은 좌석 계산에 쓴다
    int sumTakenSeats(Long lotteryId);

    // 아직 WAITLISTED인 응모를 순위 순으로
    List<DrawEntrant> findWaitlist(Long lotteryId);

    // 고른 예비자를 WON으로 바꾼다. WAITLISTED일 때만 바꾸고 바꾼 수를 돌려준다
    int promoteToWon(List<Long> entryIds);

    // 승계 마감이 지난 DRAWN 추첨의 남은 예비자를 LOST로 바꾸고 바꾼 수를 돌려준다(D14)
    int closeExpiredWaitlists();

    // 아직 끝나지 않은 응모(WON, 또는 승계될 수 있는 WAITLISTED)가 있는지. 있으면 종결하지 않는다(D14)
    boolean hasUnsettled(Long lotteryId);
}
