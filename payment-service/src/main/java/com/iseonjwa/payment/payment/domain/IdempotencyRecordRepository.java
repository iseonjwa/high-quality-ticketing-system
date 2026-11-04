package com.iseonjwa.payment.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository {
    Optional<IdempotencyRecord> find(Long userId, UUID idemKey);

    // 기본키가 겹치면 DataIntegrityViolationException이다. TX1은 이 위반을 정상 흐름으로 다시 조회한다.
    IdempotencyRecord save(IdempotencyRecord record);

    /**
     * 새 키를 기존 결제에 연결하는 ALIAS를 저장한다. 같은 키가 동시에 오면 한쪽만 저장되고 다른 쪽은 false다.
     */
    boolean linkAlias(Long userId, UUID idemKey, String requestHash, Long paymentId, Instant now);

    // ORIGIN이고 아직 응답이 없을 때만 저장한다. 반환값은 실제로 저장했는지다.
    boolean saveResponse(Long userId, UUID idemKey, Long paymentId, int status, String body);
}
