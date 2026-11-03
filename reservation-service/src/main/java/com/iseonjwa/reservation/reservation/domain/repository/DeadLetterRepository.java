package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.DeadLetter;

public interface DeadLetterRepository {
    // 같은 event_key가 이미 있으면 넣지 않고 false를 돌려준다
    boolean saveIfAbsent(DeadLetter deadLetter);
}
