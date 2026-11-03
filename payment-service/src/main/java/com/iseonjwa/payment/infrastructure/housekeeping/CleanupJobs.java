package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.payment.infrastructure.job.BackgroundJob;

import java.util.function.IntSupplier;

/** 정리 배치를 {@code housekeeping} 실행기의 작업으로 바꾼다. */
final class CleanupJobs {
    private CleanupJobs() {}

    /**
     * 한 주기에 배치를 반복하는 작업을 만든다. 배치가 {@code batchSize}보다 적게 지웠으면(지울 게 바닥났거나 잠금을 못
     * 잡아 0) 그 주기를 끝낸다. 가득 지웠으면 {@code pauseBetweenBatches}만큼 쉬고 이어 간다. 한 주기의 배치 수는
     * {@code maxBatchesPerTick}로 막아 큰 적체가 DB를 오래 붙잡지 않게 한다. 남은 양은 다음 주기가 이어서 지운다.
     */
    static BackgroundJob create(String name, HousekeepingProperties properties, IntSupplier deleteBatch) {
        return new BackgroundJob(name, properties.interval(), properties.maxBatchesPerTick(), () -> {
            var deleted = deleteBatch.getAsInt();
            if (deleted < properties.batchSize()) return false;
            return pause(properties);
        });
    }

    private static boolean pause(HousekeepingProperties properties) {
        try {
            Thread.sleep(properties.pauseBetweenBatches());
            return true;
        } catch (InterruptedException interrupted) {
            // 종료 중이다. 인터럽트 상태를 되살려 스케줄러가 주기를 끝내게 한다
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
