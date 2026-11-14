package com.iseonjwa.reservation.queue.domain;

//
public record QueueStats(long joined, long admitted, long waiting, long admittedUnissued, long activeTokens) {

    public static final QueueStats EMPTY = new QueueStats(0, 0, 0, 0, 0);


    public QueueStats plus(QueueStats s) {
        return new QueueStats(joined + s.joined, admitted + s.admitted, waiting + s.waiting,
                admittedUnissued + s.admittedUnissued, activeTokens + s.activeTokens);
    }
}
