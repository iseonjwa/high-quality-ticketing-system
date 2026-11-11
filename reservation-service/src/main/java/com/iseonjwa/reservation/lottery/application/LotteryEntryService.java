package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.ErrorCode;
import com.iseonjwa.reservation.lottery.domain.LotteryErrorCode;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.domain.entity.LotteryEntry;
import com.iseonjwa.reservation.lottery.domain.entity.LotteryEntryStatus;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.repository.LotteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 대기열·입장 토큰을 거치지 않는 동기 INSERT 기준선의 추첨 응모·취소·조회로, 빌링키는 결제 시점에 확인한다(D3·D4·D5, details.md 2절).
 */
@Service
@RequiredArgsConstructor
public class LotteryEntryService {

    private final LotteryRepository lotteryRepository;
    private final LotteryEntryRepository lotteryEntryRepository;

    // 멱등은 응모 행의 request_id UNIQUE가 맡는다. idempotency_records를 쓰지 않는다(선점의 seat_hold_requests와 같은 방식)
    @Transactional
    public LotteryEntry enter(Long userId, UUID requestId, Long lotteryId, int quantity) {
        // 같은 멱등키의 재요청이면 새로 저장하지 않고 처음 응모를 돌려준다
        Optional<LotteryEntry> previousEntry = findPreviousEntry(userId, requestId, lotteryId, quantity);
        if (previousEntry.isPresent()) {
            return previousEntry.get();
        }

        Optional<Long> insertedId = lotteryEntryRepository.insertIfOpen(lotteryId, requestId, userId, quantity);
        if (insertedId.isPresent()) {
            // 방금 저장한 행은 항상 조회되므로, 없으면 서버 이상으로 보고 500으로 응답한다
            return lotteryEntryRepository.findById(insertedId.get()).orElseThrow();
        }

        // INSERT가 0행이면 같은 멱등키로 동시에 들어온 앞 요청이 방금 커밋했을 수 있어 다시 조회한다
        return findPreviousEntry(userId, requestId, lotteryId, quantity)
                .orElseThrow(() -> new BusinessException(rejectReason(userId, lotteryId, quantity)));
    }

    // 취소를 두 번 보내도 결과가 같게, 이미 취소한 응모는 그대로 돌려준다
    @Transactional
    public LotteryEntry cancel(Long userId, Long lotteryId) {
        boolean canceled = lotteryEntryRepository.cancelIfOpen(lotteryId, userId);
        LotteryEntry lotteryEntry = findEntry(lotteryId, userId);

        // 이번에 취소하지 못했고 이미 취소된 응모도 아니면 마감됐거나 추첨이 끝난 응모다
        if (!canceled && lotteryEntry.getStatus() != LotteryEntryStatus.CANCELED) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_NOT_OPEN);
        }

        return lotteryEntry;
    }

    @Transactional(readOnly = true)
    public LotteryEntry myEntry(Long userId, Long lotteryId) {
        return findEntry(lotteryId, userId);
    }

    @Transactional(readOnly = true)
    public Lottery findLottery(Long lotteryId) {
        return lotteryRepository.findById(lotteryId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 공연 상세가 회차마다 부르는 조회라 추첨이 없는 회차는 404로 알려 응모 버튼을 감추게 한다
    public Lottery findLotteryOfSchedule(Long scheduleId) {
        return lotteryRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    private LotteryEntry findEntry(Long lotteryId, Long userId) {
        return lotteryEntryRepository.findByLotteryIdAndUserId(lotteryId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 같은 멱등키가 있으면 같은 요청일 때만 처음 결과를 돌려주고, 내용이 다르면 409로 거절한다
    private Optional<LotteryEntry> findPreviousEntry(Long userId, UUID requestId, Long lotteryId, int quantity) {
        Optional<LotteryEntry> existing = lotteryEntryRepository.findByRequestId(requestId);
        if (existing.isPresent() && !existing.get().isSameRequest(userId, lotteryId, quantity)) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        return existing;
    }

    private ErrorCode rejectReason(Long userId, Long lotteryId, int quantity) {
        Lottery lottery = lotteryRepository.findById(lotteryId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        if (lotteryEntryRepository.findByLotteryIdAndUserId(lotteryId, userId).isPresent()) {
            return LotteryErrorCode.LOTTERY_ALREADY_ENTERED;
        }
        if (quantity > lottery.getMaxPerEntry()) {
            return CommonErrorCode.VALIDATION_FAILED;
        }

        return LotteryErrorCode.LOTTERY_NOT_OPEN;
    }
}
