package com.iseonjwa.reservation.queue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AdmissionRuleTest {

    @Test
    void 토큰을_받았으면_순번과_상관없이_READY다() {
        assertThat(AdmissionRule.admissionStatus(true, null, null)).isEqualTo(AdmissionStatus.READY);
    }

    @Test
    void 순번이_입장_순번과_같으면_READY다() {
        assertThat(AdmissionRule.admissionStatus(false, 100L, 100L)).isEqualTo(AdmissionStatus.READY);
    }

    @Test
    void 순번이_입장_순번보다_크면_WAITING이다() {
        assertThat(AdmissionRule.admissionStatus(false, 101L, 100L)).isEqualTo(AdmissionStatus.WAITING);
    }

    @Test
    void 남은_순번은_순번에서_입장_순번을_뺀_값이다() {
        assertThat(AdmissionRule.position(15334, 100)).isEqualTo(15234);
    }

    @Test
    void 이미_차례가_지났으면_남은_순번은_0이다() {
        assertThat(AdmissionRule.position(90, 100)).isZero();
    }
}
