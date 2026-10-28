package com.iseonjwa.payment.event.application;

import com.iseonjwa.payment.event.domain.ProcessedEventRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 이벤트 소비의 중복을 막는다(SA 10-4). 처리 기록과 업무 변경을 한 트랜잭션에 묶어, 업무가 롤백되면
 * 처리 기록도 사라져 재전달 때 다시 처리된다.
 */
@Service
public class ProcessedEventService {
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public ProcessedEventService(ProcessedEventRepository processedEvents,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /**
     * 처음 보는 (eventId, consumerGroup)일 때만 업무를 실행한다.
     *
     * @return 업무를 실행했으면 true, 이미 처리한 이벤트라 건너뛰었으면 false
     */
    @Transactional
    public boolean processOnce(UUID eventId, String consumerGroup, Runnable action) {
        if (!processedEvents.markProcessed(eventId, consumerGroup, clock.instant())) {
            return false;
        }
        action.run();
        return true;
    }
}
