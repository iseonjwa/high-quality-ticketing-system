package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.reservation.lottery.application.LotteryEntryService;
import com.iseonjwa.reservation.lottery.domain.entity.Lottery;
import com.iseonjwa.reservation.lottery.domain.entity.LotteryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 추첨 공개 조회의 serverTime이 주입한 Clock 값과 같은지 본다. 실제 Clock 빈 연결은 LotteryEntryApiTest가 본다
class LotteryInfoServerTimeTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final LotteryEntryService lotteryEntryService = mock(LotteryEntryService.class);
    private final Lottery lottery = mock(Lottery.class);

    @BeforeEach
    void setUp() {
        when(lottery.getStatus()).thenReturn(LotteryStatus.OPEN);
        when(lotteryEntryService.findLottery(1L)).thenReturn(lottery);
        when(lotteryEntryService.findLotteryOfSchedule(10L)).thenReturn(lottery);
    }

    @Test
    void 추첨_단건_조회는_Clock의_현재_시각을_serverTime으로_내보낸다() {
        var response = new LotteryController(lotteryEntryService, CLOCK).lottery(1L);

        assertEquals(NOW, response.getBody().data().serverTime());
    }

    @Test
    void 회차의_추첨_조회도_Clock의_현재_시각을_serverTime으로_내보낸다() {
        var response = new ScheduleLotteryController(lotteryEntryService, CLOCK).lotteryOfSchedule(10L);

        assertEquals(NOW, response.getBody().data().serverTime());
    }
}
