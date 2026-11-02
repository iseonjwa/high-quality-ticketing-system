package com.iseonjwa.payment.payment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecheckScheduleTest {

    @ParameterizedTest
    @CsvSource({"1,10", "2,10", "3,10", "4,10", "5,10", "6,60", "7,300", "8,900"})
    void 시도_횟수별_다음_간격(int attempts, long seconds) {
        assertEquals(Optional.of(Duration.ofSeconds(seconds)), RecheckSchedule.delayAfter(attempts));
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 100})
    void 아홉_번째부터는_소진이다(int attempts) {
        assertEquals(Optional.empty(), RecheckSchedule.delayAfter(attempts));
    }

    @Test
    void 시도_횟수는_1_이상이다() {
        assertThrows(IllegalArgumentException.class, () -> RecheckSchedule.delayAfter(0));
    }
}
