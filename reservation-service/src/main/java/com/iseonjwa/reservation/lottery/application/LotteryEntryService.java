package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.ErrorCode;
import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryStatus;
import com.iseonjwa.reservation.lottery.domain.LotteryErrorCode;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 추첨 응모·취소·조회(D3·D5, details.md 2절). 응모는 대기열·입장 토큰을 거치지 않는 동기 INSERT 기준선이다(D3).
 * 빌링키는 응모 때 확인하지 않고 결제 시점에 없으면 승계한다(D4).
 */
@Service
@RequiredArgsConstructor
public class LotteryEntryService {

    private final LotteryRepository lotteryRepository;
    private final LotteryEntryRepository lotteryEntryRepository;

    // 멱등은 응모 행의 request_id UNIQUE가 맡는다. idempotency_records를 쓰지 않는다(선점의 seat_hold_requests와 같은 방식).
    // INSERT가 0행이면 같은 멱등키를 다시 본다. 같은 키로 동시에 들어온 앞 요청이 방금 커밋했을 수 있기 때문이다
    @Transactional
    public LotteryEntry enter(Long userId, UUID requestId, Long lotteryId, int quantity) {
        var replay = findReplay(userId, requestId, lotteryId, quantity);
        if (replay.isPresent()) {
            return replay.get();
        }
        var insertedId = lotteryEntryRepository.insertIfOpen(lotteryId, requestId, userId, quantity);
        if (insertedId.isPresent()) {
            return lotteryEntryRepository.findById(insertedId.get()).orElseThrow();
        }
        return findReplay(userId, requestId, lotteryId, quantity)
                .orElseThrow(() -> new BusinessException(rejectReason(userId, lotteryId, quantity)));
    }

    // 취소를 두 번 보내도 결과가 같게, 이미 취소한 응모는 그대로 돌려준다
    @Transactional
    public LotteryEntry cancel(Long userId, Long lotteryId) {
        boolean canceled = lotteryEntryRepository.cancelIfOpen(lotteryId, userId);
        var entry = lotteryEntryRepository.findByLotteryIdAndUserId(lotteryId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!canceled && entry.getStatus() != LotteryEntryStatus.CANCELED) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_NOT_OPEN);
        }
        return entry;
    }

    @Transactional(readOnly = true)
    public LotteryEntry myEntry(Long userId, Long lotteryId) {
        return lotteryEntryRepository.findByLotteryIdAndUserId(lotteryId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Lottery lottery(Long lotteryId) {
        return lotteryRepository.findById(lotteryId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 같은 멱등키가 있으면 같은 요청일 때만 처음 결과를 돌려준다. 내용이 다르면 409다
    private Optional<LotteryEntry> findReplay(Long userId, UUID requestId, Long lotteryId, int quantity) {
        var existing = lotteryEntryRepository.findByRequestId(requestId);
        if (existing.isPresent() && !existing.get().isSameRequest(userId, lotteryId, quantity)) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return existing;
    }

    private ErrorCode rejectReason(Long userId, Long lotteryId, int quantity) {
        var lottery = lotteryRepository.findById(lotteryId)
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
