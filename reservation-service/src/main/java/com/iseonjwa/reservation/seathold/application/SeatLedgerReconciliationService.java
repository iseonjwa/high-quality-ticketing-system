package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository.HeldCountMismatch;
import com.iseonjwa.reservation.seathold.domain.repository.SeatLedgerRepository.SeatStatusMismatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * 회차 좌석 상태와 보유 매수를 활성 예매 좌석 원장에 맞춘다(ISE-14, SA 12-7 D2). 부하·장애 주입 테스트 뒤 정합성을 확인하고,
 * 만료·결제 결과 처리가 경고만 남기고 넘어간 어긋남(hold-expiry.md 2절 6번)을 고친다. 원장({@code reservation_seats})은 바꾸지 않는다.
 *
 * <p>Note: 찾은 어긋남을 그대로 덮어쓰지 않는다. 찾는 쿼리와 보정 사이에 선점·만료·결제 확정이 커밋될 수 있어서다.
 * 한 건마다 트랜잭션을 열고 고칠 행(회차 좌석 또는 보유 매수)을 먼저 잠근 뒤 원장을 다시 읽어 계산한다. 좌석·매수를 바꾸는 트랜잭션은
 * 모두 같은 트랜잭션에서 그 행도 바꾸므로, 잠금을 얻은 뒤의 새 문장은 그 트랜잭션이 커밋한 결과를 본다(READ COMMITTED).
 * 한 트랜잭션에서 행 하나만 잠가 다른 트랜잭션과 서로 기다리는 교착이 생기지 않는다.
 */
@Service
public class SeatLedgerReconciliationService {
    // 한 번 실행에서 종류마다 고치는 최대 건수. 남은 건은 결과의 remaining으로 알리고 다시 실행해 이어서 고친다
    static final int MAX_REPAIRS = 1000;
    // 사람 감사자가 없는 시스템 보정이다(SA 12-1)
    private static final Long SYSTEM_ACTOR = null;

    private static final Logger log = LoggerFactory.getLogger(SeatLedgerReconciliationService.class);

    private final SeatLedgerRepository ledger;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SeatLedgerReconciliationService(SeatLedgerRepository ledger, TransactionTemplate transaction,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.ledger = ledger;
        this.transaction = transaction;
        this.clock = clock;
    }

    // 읽기만 한다. 부하 테스트 판정처럼 고치지 않고 건수만 볼 때 쓴다
    @Transactional(readOnly = true)
    public LedgerInspection inspect(Long scheduleId) {
        return new LedgerInspection(scheduleId,
                ledger.findSeatStatusMismatches(scheduleId, MAX_REPAIRS),
                ledger.findHeldCountMismatches(scheduleId, MAX_REPAIRS));
    }

    /**
     * 어긋난 좌석·보유 매수를 원장 기준으로 고친다. 찾은 뒤 고치기 전에 다른 트랜잭션이 이미 맞춰 놓았으면 그 건은 고친 수에 넣지 않는다.
     */
    public LedgerRepair reconcile(Long scheduleId) {
        var found = inspect(scheduleId);
        var repairedSeats = 0;
        for (var mismatch : found.seatMismatches()) {
            if (repairSeat(scheduleId, mismatch)) {
                repairedSeats++;
            }
        }
        var repairedHeldCounts = 0;
        for (var mismatch : found.heldCountMismatches()) {
            if (repairHeldCount(scheduleId, mismatch)) {
                repairedHeldCounts++;
            }
        }
        var remaining = inspect(scheduleId);
        return new LedgerRepair(scheduleId, found.seatMismatches().size(), repairedSeats,
                found.heldCountMismatches().size(), repairedHeldCounts, remaining);
    }

    private boolean repairSeat(Long scheduleId, SeatStatusMismatch mismatch) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var actual = ledger.lockSeatStatus(scheduleId, mismatch.scheduleSeatId());
            if (actual.isEmpty()) {
                return false;
            }
            var expected = ledger.expectedSeatStatus(mismatch.scheduleSeatId());
            if (ledger.updateSeatStatus(mismatch.scheduleSeatId(), expected, clock.instant(), SYSTEM_ACTOR) == 0) {
                return false;
            }
            log.warn("event=SEAT_LEDGER_REPAIRED target=schedule_seat scheduleId={} scheduleSeatId={} from={} to={}",
                    scheduleId, mismatch.scheduleSeatId(), actual.get(), expected);
            return true;
        }));
    }

    private boolean repairHeldCount(Long scheduleId, HeldCountMismatch mismatch) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var now = clock.instant();
            var actual = ledger.lockHeldCount(mismatch.userId(), scheduleId, now, SYSTEM_ACTOR);
            var expected = ledger.countActiveSeats(mismatch.userId(), scheduleId);
            if (ledger.updateHeldCount(mismatch.userId(), scheduleId, expected, now, SYSTEM_ACTOR) == 0) {
                return false;
            }
            log.warn("event=SEAT_LEDGER_REPAIRED target=held_count scheduleId={} userId={} from={} to={}",
                    scheduleId, mismatch.userId(), actual, expected);
            return true;
        }));
    }

    // 종류마다 최대 MAX_REPAIRS건까지만 담는다. 건수가 그 값이면 더 있을 수 있다
    public record LedgerInspection(Long scheduleId, List<SeatStatusMismatch> seatMismatches,
            List<HeldCountMismatch> heldCountMismatches) {
        public boolean consistent() {
            return seatMismatches.isEmpty() && heldCountMismatches.isEmpty();
        }
    }

    public record LedgerRepair(Long scheduleId, int seatMismatches, int repairedSeats, int heldCountMismatches,
            int repairedHeldCounts, LedgerInspection remaining) {}
}
