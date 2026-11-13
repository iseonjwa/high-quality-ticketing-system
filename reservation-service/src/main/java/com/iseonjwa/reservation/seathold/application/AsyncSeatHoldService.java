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

    public AsyncSeatHoldService(SeatHoldInventoryRepository inventory, SeatHoldRequestRepository seatHoldRequests,
            OutboxWriter outbox, TransactionTemplate transaction, @Qualifier("commonAuditingClock") Clock clock) {
        this.inventory = inventory;
        this.seatHoldRequests = seatHoldRequests;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** 접수한 요청의 ID(= 멱등키)를 돌려준다. 같은 키 재요청이면 기존 요청의 상태와 상관없이 같은 ID다(contracts.md 3-2). */
    public UUID accept(HoldSeatsCommand command) {
        var seatIds = SeatHoldRules.sortedDistinctSeatIds(command.seatIds());
        try {
            return transaction.execute(status -> acceptOnce(command, seatIds));
        } catch (EntryTokenAlreadyUsedException exception) {
            // 같은 토큰의 다른 요청이 먼저 접수됐다. 같은 멱등키 연타가 이 인덱스에 먼저 걸렸을 수 있어 내 행부터 본다
            var committed = seatHoldRequests.findById(command.requestId())
                    .orElseThrow(() -> new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED));
            SeatHoldRules.requireSameRequest(committed, command, seatIds);
            return committed.getRequestId();
        } catch (DataIntegrityViolationException exception) {
            // Note: 같은 키가 거의 동시에 두 번 오면 둘 다 사전 조회를 통과하고 늦은 쪽이 PK 위반을 만난다(contracts.md 3-2 ⑥).
            // 실패한 트랜잭션은 되돌려졌으므로 새로 읽어 먼저 커밋된 요청과 같은 요청인지 본다. 행이 없으면 다른 제약 위반이다
            var committed = seatHoldRequests.findById(command.requestId()).orElseThrow(() -> exception);
            SeatHoldRules.requireSameRequest(committed, command, seatIds);
            return committed.getRequestId();
        }
    }

    private UUID acceptOnce(HoldSeatsCommand command, List<Long> seatIds) {
        var previous = seatHoldRequests.findById(command.requestId());
        if (previous.isPresent()) {
            SeatHoldRules.requireSameRequest(previous.get(), command, seatIds);
            return previous.get().getRequestId();
        }
        // Note: 앞선 요청이 아직 처리 중(REQUESTED)이어도 거절한다. 같은 토큰으로 여러 요청을 줄에 넣어 두는 것을 막는다.
        // 앞선 요청이 소비자에서 거절(REJECTED)되면 토큰은 다시 쓸 수 있다. 대기열 issued 정리는 HELD를 커밋한 소비자가 한다(ISE-17)
        SeatHoldRules.requireUnusedEntryToken(seatHoldRequests, command);
        var schedule = inventory.findSchedule(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        SeatHoldRules.requireWithinPerUserLimit(schedule, seatIds);
        var now = clock.instant();
        SeatHoldRules.requireOnSale(schedule, now);
        var seats = inventory.findSeats(schedule.getId(), seatIds);
        if (seats.size() != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        requireAllHoldable(seats);

        seatHoldRequests.save(SeatHoldRequest.request(command.requestId(), command.userId(), schedule.getId(),
                seatIds, command.entryTokenJti()));
        var section = representativeSection(seats);
        outbox.write(TOPIC, schedule.getId() + ":" + section, EVENT_TYPE, VERSION,
                new SeatHoldRequested(command.requestId(), command.userId(), schedule.getId(), section, seatIds,
                        command.entryTokenJti(), now));
        return command.requestId();
    }

    // Note: 잠그지 않고 읽은 상태라 그사이 바뀔 수 있다. 여기서 AVAILABLE이어도 소비자의 조건부 UPDATE가 최종 판단이고,
    // 여기서 잡혀 보인 좌석은 같은 순간 동기형도 409를 준다. 오픈 피크 요청 대부분이 이 경우라 줄에 넣기 전에 거른다(ADR 0057)
    private static void requireAllHoldable(List<ScheduleSeat> seats) {
        for (var seat : seats) {
            if (!seat.isHoldable()) {
                throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
            }
        }
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
