package com.iseonjwa.reservation.lottery.domain;

import java.util.List;
import java.util.Optional;

public interface LotteryRepository {
    Lottery save(Lottery lottery);

    // 지운 추첨도 센다. UNIQUE(schedule_id)가 지운 행까지 막기 때문이다
    boolean existsByScheduleId(Long scheduleId);

    Optional<Lottery> findById(Long id);

    // 수정 때 회차 행을 먼저 잠그려고 추첨 엔티티를 읽기 전에 회차 ID만 꺼낸다
    Optional<Long> findScheduleIdById(Long id);

    // 응모 마감이 지난 OPEN 추첨을 한 문장으로 CLOSED로 바꾸고 바꾼 수를 돌려준다. 마감 판정은 DB 시각이다
    int closeDueLotteries();

    // 추첨 시각이 지난 CLOSED 추첨 ID
    List<Long> findDrawDueIds();

    /**
     * CLOSED이고 추첨 시각이 지난 추첨만 DRAWN으로 바꾼다(D8 재실행 방지). 같은 트랜잭션에 잠금 대기 상한을 건다.
     *
     * @return 바꿨으면 true. 이미 추첨했거나 아직 시각 전이면 false
     */
    boolean markDrawn(Long id);

    // 승계 마감(추첨 후 30분) 전인 DRAWN 추첨 ID
    List<Long> findSuccessionOpenIds();

    /**
     * 승계할 추첨 행에 잠금을 건다. 다른 워커가 잡고 있거나 승계 마감이 지났으면 비어 있다(D13 추첨 행 잠금으로 직렬화).
     *
     * @return 추첨 좌석 수. 부르는 쪽 트랜잭션이 끝날 때까지 잠금이 유지된다
     */
    Optional<Integer> lockForSuccession(Long id);

    // 미종결 응모(WON·WAITLISTED)가 없는 DRAWN 추첨 ID. 종결 후보다
    List<Long> findFinishDueIds();

    /**
     * 종결할 DRAWN 추첨 행에 잠금을 건다. 다른 워커가 잡고 있으면 비어 있다. 승계 워커와 같은 행 잠금이라 승계와 종결이 엇갈리지 않는다.
     *
     * @return 잠근 추첨. 부르는 쪽 트랜잭션이 끝날 때까지 잠금이 유지된다
     */
    Optional<Lottery> lockForFinish(Long id);

    // DRAWN일 때만 DONE으로 바꾼다
    boolean markDone(Long id);
}
