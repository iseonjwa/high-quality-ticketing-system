package com.iseonjwa.reservation.queue.domain;

import java.util.List;
import java.util.Optional;

public interface ScheduleReader {
    Optional<ScheduleTimes> findById(long id);
    List<ScheduleTimes> findOnSale();
}
