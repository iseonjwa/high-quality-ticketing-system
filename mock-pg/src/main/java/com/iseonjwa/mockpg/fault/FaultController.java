package com.iseonjwa.mockpg.fault;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 장애 주입 설정 API. 운영형 모드만 있으므로 test·loadtest에서 연다. local·prod에서는 빈이 없어 404다(mock-pg.md 5절).
 */
@Profile({"test", "loadtest"})
@RestController
@RequestMapping("/mock-pg/faults")
class FaultController {
    private final FaultInjector faults;

    FaultController(FaultInjector faults) {
        this.faults = faults;
    }

    @PostMapping
    FaultRule apply(@RequestBody FaultRule rule) {
        faults.apply(rule);
        return rule;
    }

    @GetMapping
    List<FaultRule> current() {
        return faults.currentRules();
    }
}
