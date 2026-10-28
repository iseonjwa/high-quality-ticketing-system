package com.iseonjwa.payment.payment.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatusActorTest {
    @Test
    void 운영자는_OPS_접두어로_기록한다() {
        assertEquals("OPS:kim", StatusActor.ops("kim").value());
    }

    @Test
    void 운영자_식별자가_없으면_만들지_못한다() {
        assertThrows(IllegalArgumentException.class, () -> StatusActor.ops(null));
        assertThrows(IllegalArgumentException.class, () -> StatusActor.ops(" "));
    }

    @Test
    void 이력_컬럼_길이를_넘는_행위자는_만들지_못한다() {
        assertThrows(IllegalArgumentException.class, () -> StatusActor.ops("x".repeat(37)));
    }
}
