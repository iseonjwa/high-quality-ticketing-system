package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.queue.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueProperties queueProperties;
    private final PollPolicy pollPolicy;

    private final QueueRepository queueRepository;
    private final ScheduleReader scheduleReader;

    private final EntryTokenIssuer entryTokenIssuer;


    public QueueEnterResult enter(Long scheduleId, Long userId) {
        ScheduleTimes scheduleTimes = scheduleReader.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        EnterOutcome enterOutcome = queueRepository.enter(
                scheduleId,
                userId,
                scheduleTimes.openAt().toEpochMilli(),
                scheduleTimes.startsAt().toEpochMilli(),
                queueProperties.entryTokenTtl().toMillis(),
                scheduleTimes.startsAt().plus(queueProperties.keyRetentionAfterStart()).getEpochSecond(),
                queueProperties.turnTtl().toMillis());

        // 아직 판매시간이 되지 않음
        if (enterOutcome.type() == EnterOutcome.Type.SALE_NOT_OPEN
                || enterOutcome.type() == EnterOutcome.Type.SALE_CLOSED) {
            throw new BusinessException(QueueErrorCode.SALE_NOT_OPEN);
        }

        boolean joined = enterOutcome.type() == EnterOutcome.Type.JOINED;

        // 토큰을 발급받을 수 있는 상태라면
        if (enterOutcome.type() == EnterOutcome.Type.READY) {
            return new QueueEnterResult(joined, QueuePositionResult.ready());
        }

        // 줄에 서있거나, 새로 줄에 선 경우
        long position = AdmissionRule.position(enterOutcome.seq(), enterOutcome.admitted());
        long estimatedWaitSeconds = pollPolicy.estimatedWaitSeconds(position);
        long nextPollAfterMillis = pollPolicy.nextPollAfterMillis(estimatedWaitSeconds);
        QueuePositionResult queuePositionResult = QueuePositionResult.waiting(position, estimatedWaitSeconds, nextPollAfterMillis);
        return new QueueEnterResult(joined, queuePositionResult);
    }

    public QueuePositionResult position(Long scheduleId, Long userId) {
        PositionOutcome positionOutcome =
                queueRepository.position(scheduleId, userId, queueProperties.entryTokenTtl().toMillis(), queueProperties.turnTtl().toMillis());

        if (positionOutcome.type() == PositionOutcome.Type.NOT_JOINED) {
            throw new BusinessException(QueueErrorCode.QUEUE_NOT_JOINED);
        }

        if (positionOutcome.type() == PositionOutcome.Type.READY) {
            return QueuePositionResult.ready();
        }

        // PositionOutcome.Type == WAITING인 경우
        long position = AdmissionRule.position(positionOutcome.seq(), positionOutcome.admitted());
        long estimatedWaitSeconds = pollPolicy.estimatedWaitSeconds(position);
        long nextPollAfterMillis = pollPolicy.nextPollAfterMillis(estimatedWaitSeconds);
        return QueuePositionResult.waiting(position, estimatedWaitSeconds, nextPollAfterMillis);
    }

    public QueueTokenResult issueToken(Long scheduleId, Long userId) {
        ScheduleTimes scheduleTimes = scheduleReader.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        IssueTokenOutcome issueTokenOutcome = queueRepository.issueToken(
                scheduleId,
                userId,
                queueProperties.entryTokenTtl().toMillis(),
                scheduleTimes.startsAt().plus(queueProperties.keyRetentionAfterStart()).getEpochSecond(),
                queueProperties.turnTtl().toMillis());

        if (issueTokenOutcome.type() == IssueTokenOutcome.Type.NOT_JOINED) {
            throw new BusinessException(QueueErrorCode.QUEUE_NOT_JOINED);
        }

        if (issueTokenOutcome.type() == IssueTokenOutcome.Type.WAITING) {
            throw new BusinessException(QueueErrorCode.QUEUE_NOT_READY);
        }

        EntryToken entryToken = entryTokenIssuer.issue(userId, scheduleId, issueTokenOutcome.issuedAt());
        return new QueueTokenResult(AdmissionStatus.ADMITTED, entryToken.value(), entryToken.expiresAt());
    }

    public void discardEntryToken(long scheduleId, long userId, String entryTokenJti) {
        Optional<Long> issuedAt = queueRepository.issuedAt(scheduleId, userId);
        if (issuedAt.isEmpty()) return;
        long at = issuedAt.get();

        if (!EntryTokenJti.of(scheduleId, userId, at).equals(entryTokenJti)) return;

        queueRepository.discardToken(scheduleId, userId, at);
    }
}
