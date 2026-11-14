package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.reservation.queue.domain.*;
import com.iseonjwa.reservation.queue.observability.QueueMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final ScheduleReader scheduleReader;
    private final QueueRepository queueRepository;
    private final QueueProperties queueProperties;
    private final QueueMetrics queueMetrics;


    // try-catch를 회차별로 둬야, 한 회차 입장처리가 실패하더라도 다음 회차 입장은 계속 시킬 수 있음
    public void admitOnSale() {
        QueueStats total = QueueStats.EMPTY;
        boolean failed = false;
        for (ScheduleTimes scheduleTimes : scheduleReader.findOnSale()) {
            try{
                queueRepository.admit(
                        scheduleTimes.id(),
                        queueProperties.admitPerSecond(),
                        queueProperties.entryTokenTtl().toMillis(),
                        queueProperties.cleanupLimit(),
                        scheduleTimes.startsAt().plus(queueProperties.keyRetentionAfterStart()).getEpochSecond());
                total = total.plus(queueRepository.stats(
                        scheduleTimes.id(), queueProperties.entryTokenTtl().toMillis()));
            } catch (QueueStorageUnavailableException e) {
                failed = true;
                log.warn("Redis가 잠시 응답하지 않아 이번 입장 처리를 건너뜁니다. scheduleId={}", scheduleTimes.id());
            } catch (RuntimeException e) {
                failed = true;
                log.error("입장 처리 또는 대기열 지표 읽기에 실패했습니다. 다음 주기에 다시 시도합니다. scheduleId={}", scheduleTimes.id(), e);
            }
        }
        // Redis 장애 때 지표가 0으로 떨어지는 것을 방지
        if (!failed) {
            queueMetrics.update(total);
        }
    }

}





