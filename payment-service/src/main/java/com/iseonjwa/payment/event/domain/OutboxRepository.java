package com.iseonjwa.payment.event.domain;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
    // 호출한 트랜잭션에 참여해 저장한다. 업무 변경과 함께 커밋되거나 함께 롤백돼야 한다.
    OutboxEvent save(OutboxEvent event);

    /**
     * 발행할 행을 집어 임대한다. 같은 키에 앞선 미발행 행이 있으면 집지 않아 키별 순서를 지키고,
     * 다른 릴레이가 임대 중인 행도 집지 않는다. 임대가 끝나면 다시 집힌다(중복 전송, 소비자가 멱등).
     */
    List<OutboxEvent> claimNext(Instant now, Instant leaseUntil, int batchSize);

    // 아직 PENDING일 때만 SENT로 바꾼다. 반환값은 실제로 바뀌었는지다.
    boolean markSent(Long id, Instant sentAt);

    // 발행 실패를 기록한다. 임대는 그대로 두어 임대가 끝난 뒤 다시 집힌다.
    void recordFailure(Long id, String error);
}
