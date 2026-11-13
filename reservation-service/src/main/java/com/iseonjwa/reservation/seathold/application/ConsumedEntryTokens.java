package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.queue.application.QueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 선점에 쓴 입장 토큰을 대기열 {@code issued}에서 지워, 사용자가 바로 다시 줄을 설 수 있게 한다(ADR 0053, ISE-207).
 * 지우지 않으면 대기열이 이미 쓴 토큰을 최대 10분 동안 다시 내준다.
 *
 * <p>Note: 반드시 선점 트랜잭션이 끝난 뒤(커넥션을 돌려준 뒤) 부른다. {@code AFTER_COMMIT} 리스너나
 * {@code TransactionSynchronization.afterCommit}은 커넥션을 반납하기 전에 돌아서, 대기열 Redis가 느리면
 * 그 시간만큼 커넥션을 쥔다. 풀이 작아(5개) Redis 지연이 선점 전체의 커넥션 대기로 번진다.
 */
@Slf4j
@Component
public class ConsumedEntryTokens {
    private final QueueService queue;

    public ConsumedEntryTokens(QueueService queue) {
        this.queue = queue;
    }

    /**
     * 대기열의 현재 기록이 이 jti일 때만 지운다. 다른 토큰이거나 이미 없으면 아무것도 하지 않아 여러 번 불러도 된다.
     * 선점은 이미 끝났으므로 정리에 실패해도 응답을 바꾸지 않는다. 실패하면 같은 토큰의 다음 선점을 거절할 때 다시 부른다.
     */
    public void discard(long scheduleId, long userId, String entryTokenJti) {
        if (entryTokenJti == null) {
            return;
        }
        try {
            queue.discardEntryToken(scheduleId, userId, entryTokenJti);
        } catch (RuntimeException exception) {
            // Redis 장애·차단기 열림(QueueStorageUnavailableException)을 포함한다. 잡지 않으면 커밋된 선점이 500으로 나간다
            log.warn("입장 토큰 대기열 정리 실패 scheduleId={} userId={}", scheduleId, userId, exception);
        }
    }
}
