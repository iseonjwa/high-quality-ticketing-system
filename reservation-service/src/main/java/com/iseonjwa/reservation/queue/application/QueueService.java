package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.queue.domain.*;
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
    private final ScheduleReader scheduleReader;


    public QueueEnterResult enter(Long scheduleId, Long userId) {
        ScheduleTimes scheduleTimes = scheduleReader.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        EnterOutcome enterOutcome = queueRepository.enter(
                scheduleId,
                userId,
                scheduleTimes.openAt().toEpochMilli(),
                queueProperties.entryTokenTtl().toMillis(),
                scheduleTimes.startsAt().plus(queueProperties.keyRetentionAfterStart()).getEpochSecond());

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

    public QueuePositionResult position(Long scheduleId, Long userId) {
        PositionOutcome positionOutcome =
                queueRepository.position(scheduleId, userId, queueProperties.entryTokenTtl().toMillis());

        if (positionOutcome.type() == PositionOutcome.Type.NOT_JOINED) {
            throw new BusinessException(QueueErrorCode.QUEUE_NOT_JOINED);
        }

        AdmissionStatus admissionStatus = AdmissionRule.admissionStatus(
                positionOutcome.type() == PositionOutcome.Type.TOKEN_ISSUED,
                positionOutcome.seq(), positionOutcome.admitted());

        if (admissionStatus == AdmissionStatus.READY) {
            return QueuePositionResult.ready();
        }

        // admissionStatus == WAITING인 경우
        long position = AdmissionRule.position(positionOutcome.seq(), positionOutcome.admitted());
        long estimatedWaitSeconds = pollPolicy.estimatedWaitSeconds(position);
        long nextPollAfterMillis = pollPolicy.nextPollAfterMillis(estimatedWaitSeconds);
        return QueuePositionResult.waiting(position, estimatedWaitSeconds, nextPollAfterMillis);
    }
}
