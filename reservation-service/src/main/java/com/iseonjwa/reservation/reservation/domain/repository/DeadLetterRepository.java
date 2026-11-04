package com.iseonjwa.reservation.reservation.domain.repository;

import com.iseonjwa.reservation.reservation.domain.entity.DeadLetter;

public interface DeadLetterRepository {
    // OPEN 건을 넣는다. 같은 event_key가 이미 있으면 넣지 않고 false를 돌려준다
    boolean saveOpenIfAbsent(DeadLetter deadLetter);

    // RESOLVED 건을 넣는다. 같은 event_key가 OPEN으로 있으면 RESOLVED로 닫는다. 이미 RESOLVED면 false를 돌려준다
    boolean saveResolved(DeadLetter deadLetter);
}
