package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReconciliationProperties;
import com.iseonjwa.payment.reconciliation.application.ReconciliationRecorder;
import com.iseonjwa.payment.reconciliation.application.ReconciliationService;
import com.iseonjwa.payment.reconciliation.application.ReservationViewLoader;
import com.iseonjwa.payment.reconciliation.domain.ChangeFeedProgress;
import com.iseonjwa.payment.reconciliation.domain.Preparation;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRound;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.ReservationView;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 규칙이 모두 판단을 마쳤을 때만 변경 목록 진행을 확정하는지 확인한다. DB 없이 가짜 규칙과 가짜 기록기로 돈다. */
class ReconciliationProgressCommitTest {
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final ChangeFeedProgress.State NEXT = new ChangeFeedProgress.State(NOW, null);

    private final ReservationViewLoader loader = mock(ReservationViewLoader.class);
    private final ReconciliationRecorder recorder = mock(ReconciliationRecorder.class);
    private final ReconciliationProperties properties =
            new ReconciliationProperties(true, Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(24));

    @SuppressWarnings("unchecked")
    private ReconciliationService service(ReconciliationRule... rules) {
        ObjectProvider<ReservationViewLoader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(loader);
        when(loader.load(any(), any())).thenReturn(new ReservationView(Set.of(), Map.of(), List.of(), NEXT));
        return new ReconciliationService(List.of(rules), recorder, properties, provider);
    }

    /** 준비 단계에서 예매 스냅숏을 요구하는 규칙. */
    private static ReconciliationRule reservationRule(RuleCode code) {
        return new ReconciliationRule() {
            @Override
            public RuleCode code() {
                return code;
            }

            @Override
            public Preparation prepare(ReconciliationRound round) {
                return new Preparation(round.reservations());
            }

            @Override
            public Evaluation evaluate(Instant now, com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy policy,
                    Preparation preparation) {
                return new Evaluation(List.of(), Set.of());
            }
        };
    }

    private static ReconciliationRecorder.RuleResult result(String rule, boolean skipped) {
        return new ReconciliationRecorder.RuleResult(rule, 0, 0, 0, skipped);
    }

    @Test
    void 모든_규칙이_판단을_마치면_진행을_확정한다() {
        when(recorder.reconcile(any(), any(), any(), any())).thenReturn(result("RC-1", false));

        service(reservationRule(RuleCode.RC_1), reservationRule(RuleCode.RC_2)).runOnce(NOW);

        verify(loader).commit(NEXT);
    }

    @Test
    void 규칙_하나가_실패하면_확정하지_않는다() {
        when(recorder.reconcile(any(), any(), any(), any()))
                .thenReturn(result("RC-1", false))
                .thenThrow(new IllegalStateException("판단 실패"));
        var service = service(reservationRule(RuleCode.RC_1), reservationRule(RuleCode.RC_2));

        assertThrows(ReconciliationService.ReconciliationFailedException.class, () -> service.runOnce(NOW));

        verify(loader, never()).commit(any());
    }

    @Test
    void 규칙_하나가_잠금_때문에_건너뛰었으면_확정하지_않는다() {
        when(recorder.reconcile(any(), any(), any(), any()))
                .thenReturn(result("RC-1", false))
                .thenReturn(result("RC-2", true));

        service(reservationRule(RuleCode.RC_1), reservationRule(RuleCode.RC_2)).runOnce(NOW);

        verify(loader, never()).commit(any());
    }

    @Test
    void 예매_스냅숏을_가져오지_않은_회차는_확정할_것이_없다() {
        var onlyLocal = new ReconciliationRule() {
            @Override
            public RuleCode code() {
                return RuleCode.RC_7;
            }

            @Override
            public Evaluation evaluate(Instant now, com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy policy,
                    Preparation preparation) {
                return new Evaluation(List.of(), Set.of());
            }
        };
        when(recorder.reconcile(any(), any(), any(), any())).thenReturn(result("RC-7", false));

        service(onlyLocal).runOnce(NOW);

        verify(loader, never()).commit(any());
    }

    @Test
    void 예매_조회가_꺼져_있으면_로더_없이_그대로_돈다() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReservationViewLoader> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        when(recorder.reconcile(any(), any(), any(), any())).thenReturn(result("RC-7", false));
        var onlyLocal = new ReconciliationRule() {
            @Override
            public RuleCode code() {
                return RuleCode.RC_7;
            }

            @Override
            public Evaluation evaluate(Instant now, com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy policy,
                    Preparation preparation) {
                return new Evaluation(List.of(), Set.of());
            }
        };

        new ReconciliationService(List.of(onlyLocal), recorder, properties, none).runOnce(NOW);

        verify(loader, never()).commit(any());
    }
}
