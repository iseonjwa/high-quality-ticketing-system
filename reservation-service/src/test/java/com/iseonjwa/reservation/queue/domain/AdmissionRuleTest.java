package com.iseonjwa.reservation.queue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AdmissionRuleTest {

    @Test
    void 남은_순번은_순번에서_입장_순번을_뺀_값이다() {
        assertThat(AdmissionRule.position(15334, 100)).isEqualTo(15234);
    }

    @Test
    void 이미_차례가_지났으면_남은_순번은_0이다() {
        assertThat(AdmissionRule.position(90, 100)).isZero();
    }
}
