package com.iseonjwa.reservation.queue.presentation.scheduling;

import com.iseonjwa.reservation.queue.application.QueueAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.admission-enabled", havingValue = "true", matchIfMissing = true)
public class QueueAdmissionScheduler {

    private final QueueAdmissionService queueAdmissionService;


    @Scheduled(fixedDelayString = "${queue.admission-interval:500ms}")
    void admit() {
        queueAdmissionService.admitOnSale();
    }

}
