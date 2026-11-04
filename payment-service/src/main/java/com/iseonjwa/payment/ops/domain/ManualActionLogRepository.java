package com.iseonjwa.payment.ops.domain;

import java.util.List;

/**
 * 감사 기록 저장소. 쓰기는 호출한 트랜잭션에 참여한다. 수정·삭제 메서드는 없고 DB 트리거도 거부한다.
 */
public interface ManualActionLogRepository {
    void insert(ManualActionLog log);
}
