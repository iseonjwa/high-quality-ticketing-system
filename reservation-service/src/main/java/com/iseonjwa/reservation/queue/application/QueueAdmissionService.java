package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.reservation.queue.domain.QueueRepository;
import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import com.iseonjwa.reservation.queue.domain.ScheduleReader;
import com.iseonjwa.reservation.queue.domain.ScheduleTimes;
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


    // try-catch를 회차별로 둬야, 한 회차 입장처리가 실패하더라도 다음 회차 입장은 계속 시킬 수 있음
    public void admitOnSale() {
        for (ScheduleTimes scheduleTimes : scheduleReader.findOnSale()) {
            try{
                queueRepository.admit(
                        scheduleTimes.id(),
                        queueProperties.admitPerSecond(),
                        queueProperties.entryTokenTtl().toMillis(),
                        queueProperties.cleanupLimit(),
                        scheduleTimes.startsAt().plus(queueProperties.keyRetentionAfterStart()).getEpochSecond());
            } catch (QueueStorageUnavailableException e) {
                log.warn("Redis가 잠시 응답하지 않아 이번 입장 처리를 건너뜁니다. scheduleId={}", scheduleTimes.id());
            } catch (RuntimeException e) {
                log.error("입장 처리에 실패했습니다. 다음 주기에 다시 시도합니다. scheduleId={}", scheduleTimes.id(), e);
            }
        }
    }

}





