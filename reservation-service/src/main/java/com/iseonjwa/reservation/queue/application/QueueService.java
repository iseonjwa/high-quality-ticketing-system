package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.queue.domain.AdmissionRule;
import com.iseonjwa.reservation.queue.domain.AdmissionStatus;
import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import com.iseonjwa.reservation.queue.domain.QueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueProperties queueProperties;
    private final PollPolicy pollPolicy;

    private final QueueRepository queueRepository;


    public QueueEnterResult enter(Long scheduleId, Long userId) {
        EnterOutcome enterOutcome = queueRepository.enter(scheduleId,
                userId, 0, queueProperties.entryTokenTtl().toMillis(), Instant.now().plus(Duration.ofDays(1)).getEpochSecond());// 수정 필요

        // 아직 판매시간이 되지 않음
        if (enterOutcome.type() == EnterOutcome.Type.SALE_NOT_OPEN) {
            throw new BusinessException(QueueErrorCode.SALE_NOT_OPEN);
        }

        // READY or WAITING
        AdmissionStatus admissionStatus = AdmissionRule.admissionStatus(
                enterOutcome.type() == EnterOutcome.Type.TOKEN_ISSUED,
                enterOutcome.seq(), enterOutcome.admitted());

        boolean joined = enterOutcome.type() == EnterOutcome.Type.JOINED;

        // 토큰을 발급받을 수 있는 상태라면
        if (admissionStatus == AdmissionStatus.READY) {
            return new QueueEnterResult(joined, QueuePositionResult.ready());
        }

        // 줄에 이미 서있거나, 새로 줄에 선 경우
        long position = AdmissionRule.position(enterOutcome.seq(), enterOutcome.admitted());
        long estimatedWaitSeconds = pollPolicy.estimatedWaitSeconds(position);
        long nextPollAfterMillis = pollPolicy.nextPollAfterMillis(estimatedWaitSeconds);

        QueuePositionResult queuePositionResult = QueuePositionResult.waiting(position, estimatedWaitSeconds, nextPollAfterMillis);

        return new QueueEnterResult(joined, queuePositionResult);
    }
}
