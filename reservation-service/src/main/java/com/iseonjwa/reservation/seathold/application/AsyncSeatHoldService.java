package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.EntryTokenAlreadyUsedException;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 접수형 좌석 선점의 접수(M4, SA 10-1 D4, ISE-16). 좌석 행을 잠그지 않고 {@code seat_hold_requests(REQUESTED)}와
 * Outbox {@code SeatHoldRequested}를 한 트랜잭션에 쓴다. 실제 선점은 소비자(ISE-17)가 한다.
 *
 * <p>접수에서 거르는 것은 동기형과 같은 입력 검증과, 이미 잡힌 좌석이다(ADR 0057). 매수 한도는 앞선 접수 요청까지
 * 반영해야 해서 소비자가 판단한다.
 *
 * <p>측정 전용 직접 발행({@code seat-hold.async.publish=direct}, ISE-18): Outbox 행을 쓰지 않고, 요청 행을 커밋한 뒤
 * {@link SeatHoldDirectPublisher}로 바로 보낸다. 키·봉투는 Outbox 경로와 같다. 같은 키 재요청은 다시 보내지 않는다.
 * 운영 기본값은 Outbox다(ADR 0057 결정 5).
 */
@Service
public class AsyncSeatHoldService {
    static final String TOPIC = "seat-hold.request";
    static final String EVENT_TYPE = "SeatHoldRequested";
    private static final int VERSION = 1;

    private final SeatHoldInventoryRepository inventory;
    private final SeatHoldRequestRepository seatHoldRequests;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SeatHoldDirectPublisher directPublisher;
    private final boolean directPublish;

    public AsyncSeatHoldService(SeatHoldInventoryRepository inventory, SeatHoldRequestRepository seatHoldRequests,
            OutboxWriter outbox, TransactionTemplate transaction, @Qualifier("commonAuditingClock") Clock clock,
            SeatHoldDirectPublisher directPublisher, @Value("${seat-hold.async.publish:outbox}") String publish) {
        this.inventory = inventory;
        this.seatHoldRequests = seatHoldRequests;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
        this.directPublisher = directPublisher;
        this.directPublish = switch (publish) {
            case "outbox" -> false;
            case "direct" -> true;
            default -> throw new IllegalArgumentException("seat-hold.async.publish는 outbox 또는 direct여야 합니다: " + publish);
        };
    }

    /** 접수한 요청의 ID(= 멱등키)를 돌려준다. 같은 키 재요청이면 기존 요청의 상태와 상관없이 같은 ID다(contracts.md 3-2). */
    public UUID accept(HoldSeatsCommand command) {
        return accept(command, SeatHoldGate.OPEN);
    }

    /**
     * Note: 동시 처리 상한(gate)의 자리는 커넥션을 쓰는 접수 트랜잭션에서만 쥔다. 직접 발행의 브로커 확인 대기는 커넥션을
     * 쓰지 않으므로 자리를 돌려준 뒤에 한다(동기형과 같은 원칙, {@link SyncSeatHoldService#hold(HoldSeatsCommand, SeatHoldGate)})
     */
    public UUID accept(HoldSeatsCommand command, SeatHoldGate gate) {
        var accepted = gate.run(() -> acceptInTransaction(command));
        if (accepted.message() != null) {
            var message = accepted.message();
            directPublisher.publish(TOPIC, message.key(), EVENT_TYPE, VERSION, message.payload());
        }
        return accepted.requestId();
    }

    // 접수 결과. 직접 발행이고 새로 접수한 요청일 때만 커밋 뒤 보낼 메시지가 있다(Outbox 경로와 같은 키 재요청은 없음)
    private record Accepted(UUID requestId, Message message) {
        static Accepted withoutMessage(UUID requestId) {
            return new Accepted(requestId, null);
        }
    }

    private record Message(String key, SeatHoldRequested payload) {}

    private Accepted acceptInTransaction(HoldSeatsCommand command) {
        var seatIds = SeatHoldRules.sortedDistinctSeatIds(command.seatIds());
        try {
            return transaction.execute(status -> acceptOnce(command, seatIds));
        } catch (EntryTokenAlreadyUsedException exception) {
            // 같은 토큰의 다른 요청이 먼저 접수됐다. 같은 멱등키 연타가 이 인덱스에 먼저 걸렸을 수 있어 내 행부터 본다
            var committed = seatHoldRequests.findById(command.requestId())
                    .orElseThrow(() -> new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED));
            SeatHoldRules.requireSameRequest(committed, command, seatIds);
            return Accepted.withoutMessage(committed.getRequestId());
        } catch (DataIntegrityViolationException exception) {
            // Note: 같은 키가 거의 동시에 두 번 오면 둘 다 사전 조회를 통과하고 늦은 쪽이 PK 위반을 만난다(contracts.md 3-2 ⑥).
            // 실패한 트랜잭션은 되돌려졌으므로 새로 읽어 먼저 커밋된 요청과 같은 요청인지 본다. 행이 없으면 다른 제약 위반이다
            var committed = seatHoldRequests.findById(command.requestId()).orElseThrow(() -> exception);
            SeatHoldRules.requireSameRequest(committed, command, seatIds);
            return Accepted.withoutMessage(committed.getRequestId());
        }
    }

    private Accepted acceptOnce(HoldSeatsCommand command, List<Long> seatIds) {
        // 판정 재료(멱등키, 토큰, 회차, 좌석)를 한 문장으로 읽는다. 판정 순서는 그대로다
        var check = SeatHoldRules.precheck(inventory, seatHoldRequests, command.scheduleId(), seatIds,
                command.requestId(), command.entryTokenJti());
        if (check.requestExists()) {
            var previous = seatHoldRequests.findById(command.requestId())
                    .orElseThrow(() -> new IllegalStateException("선점 요청 행이 사라졌습니다: " + command.requestId()));
            SeatHoldRules.requireSameRequest(previous, command, seatIds);
            return Accepted.withoutMessage(previous.getRequestId());
        }
        // Note: 앞선 요청이 아직 처리 중(REQUESTED)이어도 거절한다. 같은 토큰으로 여러 요청을 줄에 넣어 두는 것을 막는다.
        // 앞선 요청이 소비자에서 거절(REJECTED)되면 토큰은 다시 쓸 수 있다. 대기열 issued 정리는 HELD를 커밋한 소비자가 한다(ISE-17)
        if (check.tokenActive()) {
            throw new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED);
        }
        var seats = check.seats();
        var schedule = SeatHoldRules.scheduleOf(inventory, command.scheduleId(), seats);
        SeatHoldRules.requireWithinPerUserLimit(schedule, seatIds);
        var now = clock.instant();
        SeatHoldRules.requireOnSale(schedule, now);
        SeatHoldRules.requireAllSeatsOfSchedule(seats, seatIds);
        if (!SeatHoldRules.allHoldable(seats)) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }

        seatHoldRequests.save(SeatHoldRequest.request(command.requestId(), command.userId(), schedule.getId(),
                seatIds, command.entryTokenJti()));
        var section = representativeSection(seats);
        var key = schedule.getId() + ":" + section;
        var event = new SeatHoldRequested(command.requestId(), command.userId(), schedule.getId(), section, seatIds,
                command.entryTokenJti(), now);
        if (directPublish) {
            return new Accepted(command.requestId(), new Message(key, event));
        }
        outbox.write(TOPIC, key, EVENT_TYPE, VERSION, event);
        return Accepted.withoutMessage(command.requestId());
    }

    // 구역이 여럿이면 사전순으로 가장 작은 구역을 키로 쓴다. 같은 요청은 늘 같은 키를 얻는다(ADR 0056)
    private static String representativeSection(List<ScheduleSeat> seats) {
        return seats.stream()
                .map(ScheduleSeat::getSection)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    // SA 10-3 페이로드. section은 키를 만든 근거이고, 소비자는 seatIds를 기준으로 선점한다.
    // entryTokenJti는 소비자가 HELD를 커밋한 뒤 대기열 issued를 정리할 때 쓴다(ADR 0053). 입장 토큰 없이 들어오면 null이다
    record SeatHoldRequested(UUID requestId, long userId, long scheduleId, String section, List<Long> seatIds,
            String entryTokenJti, Instant requestedAt) {}
}
