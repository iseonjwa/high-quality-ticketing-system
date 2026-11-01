package com.iseonjwa.reservation.lottery.domain;

import com.iseonjwa.reservation.lottery.domain.SeatAllocator.SeatAssignment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatAllocatorTest {

    @Test
    void 남은_좌석보다_매수가_많은_응모자는_건너뛰고_다음_순위를_당첨시킨다() {
        var seats = row("A", 1, 1, 5);
        var ranked = List.of(entrant(1, 2), entrant(2, 2), entrant(3, 2), entrant(4, 1), entrant(5, 1));

        var result = SeatAllocator.draw(ranked, seats, 10);

        assertThat(winnerIds(result.winners())).containsExactly(1L, 2L, 4L);
        assertThat(seatCount(result.winners())).isEqualTo(5);
    }

    @Test
    void 건너뛴_응모자는_순위대로_예비자_앞쪽에_남는다() {
        var seats = row("A", 1, 1, 5);
        var ranked = List.of(entrant(1, 2), entrant(2, 2), entrant(3, 2), entrant(4, 1), entrant(5, 1), entrant(6, 2));

        var result = SeatAllocator.draw(ranked, seats, 10);

        assertThat(ids(result.waitlisted())).containsExactly(3L, 5L, 6L);
        assertThat(result.lost()).isEmpty();
    }

    @Test
    void 예비자는_정한_수까지이고_나머지는_탈락이다() {
        var seats = row("A", 1, 1, 2);
        var ranked = List.of(entrant(1, 1), entrant(2, 1), entrant(3, 1), entrant(4, 1), entrant(5, 1), entrant(6, 1));

        var result = SeatAllocator.draw(ranked, seats, 3);

        assertThat(winnerIds(result.winners())).containsExactly(1L, 2L);
        assertThat(ids(result.waitlisted())).containsExactly(3L, 4L, 5L);
        assertThat(ids(result.lost())).containsExactly(6L);
    }

    @Test
    void 순위가_높을수록_좌석_ID가_작은_좌석을_받는다() {
        var seats = row("A", 1, 1, 3);
        var ranked = List.of(entrant(1, 1), entrant(2, 1), entrant(3, 1));

        var result = SeatAllocator.draw(ranked, seats, 0);

        assertThat(result.winners()).extracting(w -> w.seats().get(0).seatId()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 열_끝에서_ID는_이어져도_열이_다르면_옆자리가_아니다() {
        var endOfRow1 = new LotterySeat(10, "A", 1, 20);
        var startOfRow2 = new LotterySeat(11, "A", 2, 1);

        assertThat(endOfRow1.isNextTo(startOfRow2)).isFalse();
        assertThat(new LotterySeat(10, "A", 1, 20).isNextTo(new LotterySeat(30, "A", 1, 19))).isTrue();
        assertThat(new LotterySeat(10, "A", 1, 20).isNextTo(new LotterySeat(11, "B", 1, 21))).isFalse();
    }

    @Test
    void 두_매는_ID가_이어진_열_경계보다_실제_옆자리_쌍을_받는다() {
        // 1열 끝(ID 10)과 2열 첫 자리(ID 11)는 ID만 이어지고, 2열의 ID 11·12가 실제 옆자리다.
        var seats = List.of(new LotterySeat(10, "A", 1, 20), new LotterySeat(11, "A", 2, 1), new LotterySeat(12, "A", 2, 2));

        var result = SeatAllocator.draw(List.of(entrant(1, 2)), seats, 0);

        assertThat(seatIds(result.winners().get(0))).containsExactly(11L, 12L);
    }

    @Test
    void 옆자리_쌍이_없으면_떨어진_두_좌석을_받는다() {
        var seats = List.of(new LotterySeat(1, "A", 1, 1), new LotterySeat(2, "A", 1, 3), new LotterySeat(3, "B", 1, 2));

        var result = SeatAllocator.draw(List.of(entrant(1, 2)), seats, 0);

        assertThat(seatIds(result.winners().get(0))).containsExactly(1L, 2L);
    }

    @Test
    void 승계는_돌아온_좌석에_맞는_예비자를_순위대로_찾는다() {
        // 2석이 돌아왔는데 첫 예비자가 1매면, 남은 1석에 맞지 않는 2매 예비자를 건너뛰고 다음 1매 예비자를 찾는다(D13).
        var freeSeats = row("A", 1, 1, 2);
        var waitlist = List.of(entrant(3, 1), entrant(5, 2), entrant(6, 1), entrant(7, 1));

        var winners = SeatAllocator.succeed(waitlist, freeSeats);

        assertThat(winnerIds(winners)).containsExactly(3L, 6L);
    }

    @Test
    void 승계에서_두_매_예비자가_앞이면_두_좌석을_함께_받는다() {
        var freeSeats = row("A", 1, 1, 2);
        var waitlist = List.of(entrant(3, 2), entrant(5, 1));

        var winners = SeatAllocator.succeed(waitlist, freeSeats);

        assertThat(winnerIds(winners)).containsExactly(3L);
        assertThat(seatIds(winners.get(0))).containsExactly(1L, 2L);
    }

    @Test
    void 응모_매수는_1에서_2매다() {
        assertThatThrownBy(() -> entrant(1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entrant(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_좌석이_두_번_들어오면_거절한다() {
        var seat = new LotterySeat(1, "A", 1, 1);

        assertThatThrownBy(() -> SeatAllocator.draw(List.of(entrant(1, 1)), List.of(seat, seat), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_위치의_좌석이_두_번_들어오면_거절한다() {
        var seats = List.of(new LotterySeat(1, "A", 1, 1), new LotterySeat(2, "A", 1, 1));

        assertThatThrownBy(() -> SeatAllocator.draw(List.of(entrant(1, 1)), seats, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 한 열에 좌석 ID와 번호가 함께 1씩 늘어나는 좌석들
    private static List<LotterySeat> row(String section, int rowNo, long firstId, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new LotterySeat(firstId + i, section, rowNo, i + 1))
                .toList();
    }

    private static DrawEntrant entrant(long entryId, int quantity) {
        return new DrawEntrant(entryId, quantity);
    }

    private static List<Long> ids(List<DrawEntrant> entrants) {
        return entrants.stream().map(DrawEntrant::entryId).toList();
    }

    private static List<Long> winnerIds(List<SeatAssignment> winners) {
        return winners.stream().map(w -> w.entrant().entryId()).toList();
    }

    private static List<Long> seatIds(SeatAssignment assignment) {
        return assignment.seats().stream().map(LotterySeat::seatId).toList();
    }

    private static int seatCount(List<SeatAssignment> winners) {
        return winners.stream().mapToInt(w -> w.seats().size()).sum();
    }
}
