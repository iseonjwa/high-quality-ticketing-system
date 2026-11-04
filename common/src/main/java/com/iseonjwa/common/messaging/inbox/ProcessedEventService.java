package com.iseonjwa.common.messaging.inbox;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * 이벤트 소비의 중복을 막는다(SA 10-4). 처리 기록과 업무 변경을 한 트랜잭션에 묶어, 업무가 롤백되면
 * 처리 기록도 사라져 재전달 때 다시 처리된다.
 */
public class ProcessedEventService {
    private final ProcessedEventRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProcessedEventService(ProcessedEventRepository repository, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 처음 보는 (eventId, consumerGroup)일 때만 업무를 실행한다.
     *
     * <p>Note: 호출 쪽에 이미 트랜잭션이 있으면 거기에 참여한다. 이때 업무가 예외를 던지면 바깥 트랜잭션이
     * rollback-only가 되므로, 호출한 쪽이 그 예외를 삼키고 커밋하려 하면 UnexpectedRollbackException이 난다.
     *
     * @return 업무를 실행했으면 true, 이미 처리한 이벤트라 건너뛰었으면 false
     */
    public boolean processOnce(UUID eventId, String consumerGroup, Runnable action) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!repository.markProcessed(eventId, consumerGroup, clock.instant())) return false;
            action.run();
            return true;
        }));
    }
}
