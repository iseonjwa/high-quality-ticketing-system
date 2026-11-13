package com.iseonjwa.reservation.concert.application;

import java.util.Arrays;
import java.util.List;

// 일반 선점이 잡을 수 있는 좌석 ID 묶음. 정렬해 두고 이진 탐색으로 찾는다
final class HoldableSeatIds {

    private final long[] sortedIds;

    private HoldableSeatIds(long[] sortedIds) {
        this.sortedIds = sortedIds;
    }

    // Note: 이진 탐색은 정렬된 배열에서만 맞는다. 쿼리의 ORDER BY에 기대지 않고 여기서 정렬해,
    // 쿼리가 바뀌어도 틀린 결과 대신 같은 결과가 나오게 한다. DB 정렬도 빠져 DB가 하는 일이 준다
    static HoldableSeatIds of(List<Long> seatIds) {
        long[] ids = new long[seatIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = seatIds.get(i);
        }
        Arrays.sort(ids);
        return new HoldableSeatIds(ids);
    }

    boolean contains(long seatId) {
        return Arrays.binarySearch(sortedIds, seatId) >= 0;
    }
}
