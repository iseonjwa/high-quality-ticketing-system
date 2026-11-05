package com.iseonjwa.reservation.lottery.domain.repository;

import com.iseonjwa.reservation.lottery.domain.entity.LotteryEntry;
import com.iseonjwa.reservation.lottery.domain.entity.LotteryEntryStatus;
import com.iseonjwa.reservation.lottery.domain.policy.DrawEntrant;


import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LotteryEntryRepository {

    /**
     * 응모 기간(DB 시각 기준) 안이고 1인 최대 매수 이하이며 같은 사용자·멱등키의 응모가 없을 때만 응모 행을 넣는다(details.md 2절).
     *
     * @return 넣은 응모 ID. 조건이 맞지 않거나 이미 있으면 비어 있고 이유는 호출한 쪽이 다시 읽어 가린다
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

    /**
     * 순위 계산에 넘길 추첨 대상을 읽으며, 취소한 응모는 빠진다(D5).
     */
    List<DrawEntrant> findDrawEntrants(Long lotteryId);

    /**
     * 추첨 결과 중 당첨·예비만 순위와 함께 배치로 저장한다(D8 쓰기 줄이기).
     */
    void saveDrawResults(List<DrawResult> results);

    /**
     * 남은 ENTERED를 순위 없이 한 문장으로 LOST로 바꾼다(11/2 결정).
     */
    int markRemainingLost(Long lotteryId);

    /**
     * 아직 예매가 없는 당첨 응모 하나를 순위 순으로 집어, 호출한 쪽 트랜잭션이 끝날 때까지 행 잠금을 건다(SKIP LOCKED, D10).
     */
    Optional<UnassignedWinner> lockNextUnassignedWinner(Collection<Long> skipEntryIds);

    /**
     * WON이고 아직 예매가 없는 당첨 응모에 예매를 연결하고 결제 시도 횟수를 1 올린다.
     */
    boolean linkReservation(Long entryId, Long reservationId);

    /**
     * WON이고 예매가 없는 당첨 중 예매를 만들 수 없는 응모(매수 한도 초과 등)를 FAILED로 바꾼다.
     */
    boolean markAssignmentFailed(Long entryId, String reason);

    /**
     * 남은 추첨석이 없는 당첨자를 승계 마감(추첨 후 30분)이 지났을 때만 FAILED(NO_SEAT)로 닫고, 마감 전이면 false를 돌려 다시 시도하게 한다(ISE-153).
     */
    boolean markNoSeatFailedAfterSuccessionDeadline(Long entryId);

    /**
     * 결제 확정으로, 이 예매에 연결된 WON 응모만 PAID로 바꾼다.
     */
    boolean markPaid(Long reservationId);

    /**
     * 결제 실패·만료로, 이 예매에 연결된 WON 응모만 FAILED로 바꾼다.
     */
    boolean markFailed(Long reservationId, String reason);

    /**
     * 선점 만료가 추첨 예매인지 가리고 연장 상한을 계산하도록, 예매에 연결된 WON 응모의 추첨 시각을 읽는다.
     */
    Optional<Instant> findWonDrawAtByReservationId(Long reservationId);

    /**
     * 우리 책임 실패(NOT_SENT) 재시도로, 시도가 {@code maxAttempts}보다 적고 승계 마감 전이면 예매 연결만 비워 배정 워커가 다시 시도하게 한다(D12).
     *
     * @return 재시도로 돌렸으면 true. 횟수나 시각 조건을 넘었으면 false
     */
    boolean releaseForRetry(Long reservationId, int maxAttempts);

    /**
     * 승계할 남은 좌석을 계산하도록 당첨(WON)·결제 완료(PAID) 매수를 합한다.
     */
    int sumTakenSeats(Long lotteryId);

    /**
     * 아직 WAITLISTED인 응모를 순위 순으로 읽는다.
     */
    List<DrawEntrant> findWaitlist(Long lotteryId);

    /**
     * 고른 예비자를 WAITLISTED일 때만 WON으로 바꾸고 바꾼 수를 돌려준다.
     */
    int promoteToWon(List<Long> entryIds);

    /**
     * 승계 마감이 지난 DRAWN 추첨의 남은 예비자를 LOST로 바꾸고 바꾼 수를 돌려준다(D14).
     */
    int closeExpiredWaitlists();

    /**
     * 종결 전 확인으로, 끝나지 않은 응모(WON, 또는 승계될 수 있는 WAITLISTED)가 있는지 본다(D14).
     */
    boolean hasUnsettled(Long lotteryId);

    /**
     * 모든 추첨에서 결제 결과를 기다리는 당첨자 수를 센다.
     */
    long countUnsettledWinners();

    /**
     * 예매에 연결된 응모가 속한 추첨의 추첨 시각을 읽는다.
     */
    Optional<Instant> findDrawAtByReservationId(Long reservationId);

    /**
     * @param drawRank 전체 응모 중 순위(1부터)로, 예비자는 이 값의 오름차순으로 승계한다
     */
    record DrawResult(long entryId, LotteryEntryStatus status, int drawRank) {}

    record UnassignedWinner(long entryId, long lotteryId, long scheduleId, long userId, int quantity) {}
}
