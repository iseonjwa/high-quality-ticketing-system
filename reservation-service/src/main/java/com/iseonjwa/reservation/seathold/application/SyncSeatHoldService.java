package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequestStatus;
import com.iseonjwa.reservation.seathold.domain.repository.EntryTokenAlreadyUsedException;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import com.iseonjwa.reservation.seathold.observability.SeatHoldMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 동기형 좌석 일괄 선점(M2, SA 6-4). 좌석 상태 검사 → 선점 요청 저장 → 매수 반영 → 예매·이력 저장 → 선점 요청 HELD →
 * 좌석 AVAILABLE→HELD → 예매 좌석 저장을 한 트랜잭션으로 묶는다(순서는 ADR 0062, 본체는 {@link SeatHolding}).
 * 하나라도 실패하면 전체를 롤백해 실패한 seat_hold_requests 행을 남기지 않는다(ADR 0032).
 *
 * <p>TODO: 아래는 MVP 기준선이다(ADR 0035). M4 동시성 트러블슈팅 이슈에서 문제를 재현한 뒤 바꾼다.
 * 좌석 ID 오름차순 {@code FOR UPDATE}와 {@code lock_timeout} 50ms(TS-1, ADR 0061), 매수 한도의 조건부 UPDATE 직렬화(TS-2),
 * 같은 멱등키 동시 요청의 제약 위반 재조회(TS-3). 사전 조회는 첫 요청이 커밋된 뒤의 재요청만 잡는다. 첫 트랜잭션이
 * 끝나기 전에 같은 키가 또 오면 조회가 비어 선점 경로로 들어가고, 좌석 행 잠금을 기다린 뒤 갱신 0행이 되어
 * 자기 좌석인데도 409 SEAT_ALREADY_HELD를 받는다(contracts.md 3-2 ⑥).
 */
@Service
public class SyncSeatHoldService {
    // SA 9-4. 핫 좌석에서 잠금을 오래 기다리며 커넥션을 쥐지 않게 한다. 500ms에서 50ms로 줄였다(ADR 0061).
    // Note: 0으로 두지 않는다. 롤백할 요청이 잠깐 쥔 좌석은 수 ms 안에 풀리므로 그만큼은 기다려야 빈 좌석을 놓치지 않는다
    static final Duration LOCK_TIMEOUT = Duration.ofMillis(50);

    private final SeatHoldInventoryRepository inventory;
    private final ReservationRepository reservations;
    private final SeatHolding seatHolding;
    private final SeatHoldRequestRepository seatHoldRequests;
    private final HeldReservationResults results;
    private final ConsumedEntryTokens consumedEntryTokens;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final HeldSeatPreCheck heldSeatPreCheck;
    private final SeatHoldMetrics metrics;

    public SyncSeatHoldService(SeatHoldInventoryRepository inventory, ReservationRepository reservations,
            SeatHolding seatHolding,
            SeatHoldRequestRepository seatHoldRequests, HeldReservationResults results,
            ConsumedEntryTokens consumedEntryTokens,
            @Qualifier("commonAuditingClock") Clock clock, PlatformTransactionManager transactionManager,
            HeldSeatPreCheck heldSeatPreCheck, SeatHoldMetrics metrics) {
        this.heldSeatPreCheck = heldSeatPreCheck;
        this.metrics = metrics;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.inventory = inventory;
        this.reservations = reservations;
        this.seatHolding = seatHolding;
        this.seatHoldRequests = seatHoldRequests;
        this.results = results;
        this.consumedEntryTokens = consumedEntryTokens;
        this.clock = clock;
    }

    /**
     * Note: 트랜잭션을 이 메서드 안에서 연다. 같은 멱등키의 다른 요청에 진 트랜잭션은 rollback-only가 되므로,
     * 먼저 커밋된 결과를 다시 읽는 일은 그 트랜잭션이 끝난 뒤 새 읽기 트랜잭션에서 해야 한다(ADR 0032, TS-3).
     * 입장 토큰의 대기열 정리도 트랜잭션이 끝나 커넥션을 돌려준 뒤에 한다(ADR 0053, {@link ConsumedEntryTokens}).
     */
    public SyncHoldOutcome hold(HoldSeatsCommand command) {
        return hold(command, SeatHoldGate.OPEN);
    }

    /**
     * Note: 동시 처리 상한(gate)의 자리는 커넥션을 쓰는 트랜잭션 단계(decide)에서만 쥔다. 커밋 뒤의 일(입장 토큰
     * 대기열 정리 = Redis, 결제 토큰 서명 = CPU)은 커넥션을 쓰지 않으므로 자리를 돌려준 뒤에 한다. 자리 점유 시간이
     * 줄면 같은 자리 수로 더 많이 처리한다(seathold-perf-loop.md 반복 2). 대가로 Redis가 느리면 그 지연만큼
     * 자리 밖에서 Tomcat 스레드를 쥔다(대기열 Redis 차단기가 막는다). DB를 다시 읽는 재응답과 예외 경로의 정리는
     * decide 안에 남는다
     */
    public SyncHoldOutcome hold(HoldSeatsCommand command, SeatHoldGate gate) {
        var decision = gate.run(() -> decide(command));
        if (!(decision instanceof Decision.Accepted)) {
            discardEntryToken(command);
        }
        return respond(decision);
    }

    private Decision decide(HoldSeatsCommand command) {
        var seatIds = SeatHoldRules.sortedDistinctSeatIds(command.seatIds());
        Decision decision;
        try {
            // Note: 캐시 판정은 메모리만 읽어 자리 점유 시간을 거의 늘리지 않는다. 캐시가 잡힘이라 할 때의 멱등키 재조회는
            // 커넥션을 쓰므로 동시 처리 상한 안에 둔다
            if (heldSeatPreCheck.anyNotHoldable(command.scheduleId(), seatIds)) {
                decision = replayOrRejectHeldSeat(command, seatIds);
            } else {
                decision = writeTransaction.execute(status -> holdInTransaction(command, seatIds));
            }
        } catch (ConcurrentSameKeyException lost) {
            decision = readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replay(previous, command, seatIds))
                    .orElseThrow(() -> new IllegalStateException("먼저 커밋된 선점 요청이 없습니다: " + command.requestId(),
                            lost)));
        } catch (ConcurrentEntryTokenException lost) {
            decision = replayOrRejectUsedToken(command, seatIds);
        } catch (BusinessException rejected) {
            // 앞선 선점 뒤의 정리가 실패했을 수 있어 거절할 때 한 번 더 지운다. 같은 jti일 때만 지우므로 새로 받은 토큰은 남는다
            if (rejected.getErrorCode() == SeatHoldErrorCode.ENTRY_TOKEN_USED) {
                discardEntryToken(command);
            }
            throw rejected;
        }
        return decision;
    }

    /**
     * 트랜잭션 안에서 정한 결과. 결제 토큰 서명은 트랜잭션이 끝난 뒤 {@link #respond}에서 한다.
     *
     * <p>Note: 서명(ES256)은 CPU 일이라 커밋 전에 하면 그동안 좌석 잠금과 커넥션을 쥔다. 로컬 실측 한 번에 약 0.11ms다.
     * 서명이 실패해도 선점은 이미 커밋돼 있고, 같은 멱등키로 다시 보내면 재응답 경로에서 토큰을 다시 받는다
     */
    private sealed interface Decision {
        // 결제할 수 있는 예매. 토큰을 붙여 답한다
        record Payable(Reservation reservation, List<Long> seatIds) implements Decision {}

        // 이미 확정된 예매. 토큰 없이 답한다
        record Settled(Reservation reservation, List<Long> seatIds) implements Decision {}

        record Accepted(UUID requestId) implements Decision {}
    }

    private SyncHoldOutcome respond(Decision decision) {
        return switch (decision) {
            case Decision.Payable payable -> new SyncHoldOutcome.Held(
                    results.withToken(payable.reservation(), payable.seatIds()));
            case Decision.Settled settled -> new SyncHoldOutcome.Held(
                    results.withoutToken(settled.reservation(), settled.seatIds()));
            case Decision.Accepted accepted -> new SyncHoldOutcome.Accepted(accepted.requestId());
        };
    }

    // Note: 좌석 조회의 1초 캐시가 이미 잡힌 좌석이 섞였다고 해서 확실히 질 요청으로 보고 쓰기 트랜잭션 없이 409로 끝낸다(ISE-281).
    // 거절하기 전에 같은 멱등키의 앞선 요청부터 찾는다. 성공한 선점의 재시도는 캐시에서 자기 좌석이 잡힘으로 보여,
    // 이 조회를 건너뛰면 201 대신 409를 받는다. 조회는 캐시가 잡힘이라 할 때만 하므로 통과하는 요청에는 DB 조회가 늘지 않는다.
    // 대가는 두 가지다. 1초 안에 풀린 좌석에 409가 날 수 있고, 매수·판매 기간·입장 토큰·회차 소속 검사보다 먼저 와서
    // 그 거절 사유보다 SEAT_ALREADY_HELD가 먼저 나갈 수 있다(contracts.md 1-1)
    private Decision replayOrRejectHeldSeat(HoldSeatsCommand command, List<Long> seatIds) {
        var replayed = readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                .map(previous -> replay(previous, command, seatIds)));
        if (replayed.isPresent()) {
            return replayed.get();
        }
        metrics.recordPreRejected();
        throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
    }

    // Note: 같은 토큰의 다른 요청이 먼저 커밋해 유니크 인덱스에 걸렸다. 같은 멱등키 연타가 PK보다 이 인덱스에 먼저 걸렸을 수도 있어,
    // 내 요청 행이 이미 있으면 같은 키 재요청으로 답한다. 없으면 다른 요청이 이 토큰을 썼다
    private Decision replayOrRejectUsedToken(HoldSeatsCommand command, List<Long> seatIds) {
        var replayed = readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                .map(previous -> replay(previous, command, seatIds)));
        if (replayed.isPresent()) {
            return replayed.get();
        }
        discardEntryToken(command);
        throw new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED);
    }

    private void discardEntryToken(HoldSeatsCommand command) {
        discardEntryToken(command.scheduleId(), command.userId(), command.entryTokenJti());
    }

    private void discardEntryToken(Long scheduleId, Long userId, String entryTokenJti) {
        consumedEntryTokens.discard(scheduleId, userId, entryTokenJti);
    }

    private Decision holdInTransaction(HoldSeatsCommand command, List<Long> seatIds) {
        // 같은 멱등키 재요청은 첫 결과로 답하고, 다른 요청이 이 토큰을 썼으면 좌석을 잠그기 전에 거절한다(ADR 0053 결정 2).
        // Note: 판정 재료를 한 문장으로 읽는다. 판정 순서는 그대로다(contracts.md 1절 검사 순서)
        var check = SeatHoldRules.precheck(inventory, seatHoldRequests, command.scheduleId(), seatIds,
                command.requestId(), command.entryTokenJti());
        if (check.requestExists()) {
            var previous = seatHoldRequests.findById(command.requestId())
                    .orElseThrow(() -> new IllegalStateException("선점 요청 행이 사라졌습니다: " + command.requestId()));
            return replay(previous, command, seatIds);
        }
        if (check.tokenActive()) {
            throw new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED);
        }
        var seats = check.seats();
        var schedule = SeatHoldRules.scheduleOf(inventory, command.scheduleId(), seats);
        SeatHoldRules.requireWithinPerUserLimit(schedule, seatIds);
        var now = clock.instant();
        SeatHoldRules.requireOnSale(schedule, now);
        SeatHoldRules.requireAllSeatsOfSchedule(seats, seatIds);
        // 이미 잡힌 좌석이 섞이면 멱등키·매수·좌석을 건드리지 않고 409로 끝낸다(ADR 0062).
        // Note: 멱등키를 다시 읽지 않는다. 판정 재료가 한 시점의 데이터라, 같은 키 첫 요청이 커밋된 뒤라면 위에서 멱등키 행이
        // 보여 재응답했고, 커밋 전이라면 그 요청의 좌석도 아직 비어 보여 여기 오지 않는다(쓰기에서 PK 충돌 → 재응답, TS-3)
        if (!SeatHoldRules.allHoldable(seats)) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }

        claimRequest(command, schedule.getId(), seatIds);
        // Note: 멱등키 선점 뒤에 건다. 같은 키의 연타는 첫 요청이 끝날 때까지 기다려야 재조회로 같은 결과를 줄 수 있다
        inventory.applyLockTimeout(LOCK_TIMEOUT);
        var reservation = seatHolding.hold(command.requestId(), command.userId(), schedule, seats, seatIds, now);
        return new Decision.Payable(reservation, seatIds);
    }

    // Note: 멱등키를 매수·좌석보다 먼저 쓴다. 같은 키의 두 번째 요청은 PK에서 첫 요청의 커밋을 기다린 뒤 유니크 위반을 받고,
    // 좌석 경로로 들어가 자기 좌석에 409를 받는 일이 없어진다. 첫 요청이 실패해 롤백되면 두 번째 요청이 이어서 선점한다.
    // 입장 토큰 jti도 이 행에 함께 쓴다. 같은 토큰의 동시 요청은 유니크 인덱스에서 먼저 온 요청의 커밋을 기다리고,
    // 먼저 온 요청이 성공하면 위반을, 실패해 롤백되면 이어서 선점한다
    private void claimRequest(HoldSeatsCommand command, Long scheduleId, List<Long> seatIds) {
        claim(SeatHoldRequest.request(command.requestId(), command.userId(), scheduleId, seatIds,
                command.entryTokenJti()));
    }

    private void claim(SeatHoldRequest request) {
        try {
            seatHoldRequests.save(request);
        } catch (EntryTokenAlreadyUsedException exception) {
            throw new ConcurrentEntryTokenException(exception);
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentSameKeyException(exception);
        }
    }

    /**
     * 자동 배정 선점(ISE-264). 구역·등급과 매수만 받아 서버가 좌석을 고른다. 멱등키·입장 토큰·매수 한도·판매 기간 규칙과
     * 트랜잭션 경계는 {@link #hold}와 같다. 좌석 조회 1초 캐시 사전 거절은 하지 않는다. 서버가 DB의 지금 상태로 고르기 때문이다.
     *
     * <p>Note: 설정 {@code seat-hold.mode}가 접수형이어도 이 API는 동기형으로 답한다. 고를 좌석이 없으면 접수형 메시지 키(구역)를
     * 정할 수 없고, 이번 범위는 측정에서 본 좌석 충돌·재조회를 없애는 것이라 동기형만 둔다(ADR 0059).
     */
    public SyncHoldOutcome holdAuto(AutoHoldSeatsCommand command) {
        return holdAuto(command, SeatHoldGate.OPEN);
    }

    // 자리(gate)는 직접 고르는 선점과 같이 트랜잭션 단계에서만 쥐고, 입장 토큰 정리와 결제 토큰 서명은 돌려준 뒤에 한다
    public SyncHoldOutcome holdAuto(AutoHoldSeatsCommand command, SeatHoldGate gate) {
        var decision = gate.run(() -> decideAuto(command));
        if (!(decision instanceof Decision.Accepted)) {
            discardEntryToken(command.scheduleId(), command.userId(), command.entryTokenJti());
        }
        return respond(decision);
    }

    private Decision decideAuto(AutoHoldSeatsCommand command) {
        Decision outcome;
        try {
            outcome = writeTransaction.execute(status -> holdAutoInTransaction(command));
        } catch (ConcurrentSameKeyException lost) {
            outcome = readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replayAuto(previous, command))
                    .orElseThrow(() -> new IllegalStateException("먼저 커밋된 선점 요청이 없습니다: " + command.requestId(),
                            lost)));
        } catch (ConcurrentEntryTokenException lost) {
            var replayed = readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replayAuto(previous, command)));
            if (replayed.isEmpty()) {
                discardEntryToken(command.scheduleId(), command.userId(), command.entryTokenJti());
                throw new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED);
            }
            outcome = replayed.get();
        } catch (BusinessException rejected) {
            if (rejected.getErrorCode() == SeatHoldErrorCode.ENTRY_TOKEN_USED) {
                discardEntryToken(command.scheduleId(), command.userId(), command.entryTokenJti());
            }
            throw rejected;
        }
        return outcome;
    }

    // 순서는 hold와 같다. 멱등키 행을 먼저 쓰고(좌석은 빈 배열), 매수 → 좌석 → 예매 뒤에 고른 좌석으로 행을 채운다
    private Decision holdAutoInTransaction(AutoHoldSeatsCommand command) {
        var previous = seatHoldRequests.findById(command.requestId());
        if (previous.isPresent()) {
            return replayAuto(previous.get(), command);
        }
        SeatHoldRules.requireUnusedEntryToken(seatHoldRequests, command.entryTokenJti());
        var schedule = inventory.findSchedule(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        SeatHoldRules.requireWithinPerUserLimit(schedule, command.quantity());
        var now = clock.instant();
        SeatHoldRules.requireOnSale(schedule, now);

        claim(SeatHoldRequest.autoAssign(command.requestId(), command.userId(), schedule.getId(),
                command.criteriaKey(), command.entryTokenJti()));
        // 좌석은 건너뛰어 기다리지 않지만, 같은 사용자의 다른 탭이 쥔 매수 행은 기다린다. 그 대기에 같은 시한을 건다
        inventory.applyLockTimeout(LOCK_TIMEOUT);
        var held = seatHolding.holdAuto(command.requestId(), command.userId(), schedule, command.grade(),
                command.section(), command.quantity(), now);
        if (seatHoldRequests.markAutoHeld(command.requestId(), held.reservation().getId(), held.seatIds(), now,
                command.userId()) != 1) {
            throw new IllegalStateException("자동 배정 요청을 HELD로 바꾸지 못했습니다: " + command.requestId());
        }
        return new Decision.Payable(held.reservation(), held.seatIds());
    }

    private Decision replayAuto(SeatHoldRequest previous, AutoHoldSeatsCommand command) {
        SeatHoldRules.requireSameAutoRequest(previous, command);
        return replayResult(previous, previous.getSeatIds());
    }

    private static final class ConcurrentSameKeyException extends RuntimeException {
        ConcurrentSameKeyException(Throwable cause) {
            super(cause);
        }
    }

    private static final class ConcurrentEntryTokenException extends RuntimeException {
        ConcurrentEntryTokenException(Throwable cause) {
            super(cause);
        }
    }

    // 같은 멱등키 재요청(contracts.md 3절). 좌석·매수를 다시 건드리지 않고 첫 요청이 만든 예매로 응답한다.
    // 회차·판매 기간 검사보다 먼저 와서, 그사이 판매가 끝났거나 회차가 지워져도 이미 잡은 선점 결과를 돌려준다
    private Decision replay(SeatHoldRequest previous, HoldSeatsCommand command, List<Long> seatIds) {
        SeatHoldRules.requireSameRequest(previous, command, seatIds);
        return replayResult(previous, seatIds);
    }

    private Decision replayResult(SeatHoldRequest previous, List<Long> seatIds) {
        // Note: 동기형은 HELD 행만 남기지만, 접수형으로 받은 요청(REQUESTED·REJECTED)이 남은 채 동기형으로 다시 뜰 수 있다.
        // 처리 중인 요청을 실패로 답하면 사용자가 새 좌석을 고르는 사이 앞 요청이 HELD가 되어 모르는 예매가 좌석·매수를 묶는다.
        // 그래서 접수형으로 시작한 요청은 202로 답하고 결과는 조회 API로 보게 한다(ADR 0058)
        if (previous.getStatus() != SeatHoldRequestStatus.HELD) {
            return new Decision.Accepted(previous.getRequestId());
        }
        // HELD인데 예매가 없으면 데이터가 깨진 것이다
        var reservation = reservations.findById(previous.getReservationId())
                .orElseThrow(() -> new IllegalStateException("선점 요청의 예매가 없습니다: " + previous.getRequestId()));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return new Decision.Settled(reservation, seatIds);
        }
        if (reservation.isPayableAt(clock.instant())) {
            return new Decision.Payable(reservation, seatIds);
        }
        throw new BusinessException(SeatHoldErrorCode.RESERVATION_NOT_PAYABLE);
    }

}
