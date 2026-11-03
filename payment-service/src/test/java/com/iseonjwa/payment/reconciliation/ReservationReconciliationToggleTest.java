package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.application.ReservationViewLoader;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 예매 조회를 켜지 않으면(기본, 테스트 프로필) RC-1~RC-6 규칙과 예매 조회 빈을 만들지 않는다. RC-7·RC-8은 그대로다. */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReservationReconciliationToggleTest {
    @Autowired
    List<ReconciliationRule> rules;
    @Autowired
    ApplicationContext context;

    @Test
    void 꺼져_있으면_RC7과_RC8만_있고_예매_조회_빈이_없다() {
        assertEquals(List.of(RuleCode.RC_7, RuleCode.RC_8),
                rules.stream().map(ReconciliationRule::code).sorted().toList());
        assertTrue(context.getBeansOfType(ReservationLookup.class).isEmpty());
        assertTrue(context.getBeansOfType(ReservationViewLoader.class).isEmpty());
    }
}
