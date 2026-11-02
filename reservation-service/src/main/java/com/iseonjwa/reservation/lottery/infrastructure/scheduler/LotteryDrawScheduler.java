package com.iseonjwa.reservation.lottery.infrastructure.scheduler;

import com.iseonjwa.reservation.lottery.application.LotteryDrawService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 마감·추첨 스케줄러(details.md 4절). 인스턴스마다 돌아도 안전하다. 전이는 조건부 UPDATE가 한 번만 반영한다.
 * {@code lottery.scheduler.enabled=false}면 켜지 않는다(테스트는 서비스를 직접 부른다).
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryDrawScheduler {

    private final LotteryDrawService drawService;
    private final Timer drawTimer;
    private final Counter drawFailures;
    // 연속 실패가 쌓이면 시간 초과가 반복된다는 뜻이다. 0이 아니면 경보 대상이다(D8)
    private final AtomicInteger consecutiveFailures;

    public LotteryDrawScheduler(LotteryDrawService drawService, MeterRegistry meterRegistry) {
        this.drawService = drawService;
        this.drawTimer = Timer.builder("lottery.draw.duration").description("추첨 한 번의 실행 시간")
                .register(meterRegistry);
        this.drawFailures = Counter.builder("lottery.draw.failures").description("추첨 실패 횟수")
                .register(meterRegistry);
        this.consecutiveFailures = meterRegistry.gauge("lottery.draw.consecutive.failures", new AtomicInteger());
    }

    @Scheduled(fixedDelayString = "${lottery.scheduler.close-interval:10s}")
    public void closeDueLotteries() {
        int closed = drawService.closeDueLotteries();
        if (closed > 0) {
            log.info("추첨 응모 마감: {}건", closed);
        }
    }

    // 추첨 하나가 실패해도 다음 추첨은 계속한다. 실패한 추첨은 CLOSED로 남아 다음 주기에 다시 시도된다
    @Scheduled(fixedDelayString = "${lottery.scheduler.draw-interval:10s}")
    public void drawDueLotteries() {
        for (var lotteryId : drawService.findDrawDueIds()) {
            try {
                boolean drawn = drawTimer.recordCallable(() -> drawService.draw(lotteryId));
                consecutiveFailures.set(0);
                if (drawn) {
                    log.info("추첨 실행 완료: lotteryId={}", lotteryId);
                }
            } catch (Exception exception) {
                drawFailures.increment();
                consecutiveFailures.incrementAndGet();
                log.error("추첨 실행 실패: lotteryId={}", lotteryId, exception);
            }
        }
    }
}
