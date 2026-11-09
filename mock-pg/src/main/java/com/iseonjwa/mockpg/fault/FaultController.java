package com.iseonjwa.mockpg.fault;

import com.iseonjwa.mockpg.web.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 장애 주입 설정 API. 운영형 모드만 있으므로 test·loadtest에서 연다. local·prod에서는 빈이 없어 404다(mock-pg.md 5절).
 * {@code prod}가 함께 켜지면 {@link ProfileGuard}가 기동을 막지만, 빈 조건에도 {@code !prod}를 두어 한 번 더 막는다.
 * 변조형 모드는 {@code test}에서만 받고 그 밖의 프로필에서는 400으로 거절한다(mock-pg.md 5절 프로필 제한).
 */
@Profile("(test | loadtest) & !prod")
@RestController
@RequestMapping("/mock-pg/faults")
class FaultController {
    private final FaultInjector faults;
    private final boolean tamperingAllowed;

    FaultController(FaultInjector faults, Environment environment) {
        this.faults = faults;
        this.tamperingAllowed = environment.acceptsProfiles(Profiles.of("test"));
    }

    @PostMapping
    ResponseEntity<?> apply(@RequestBody FaultRule rule) {
        // Note: 받아 두면 부하 측정 중 PG가 잘못 응답하는 서버가 되므로 설정하는 시점에 거절한다
        if (rule.mode().tampering() && !tamperingAllowed) {
            return ResponseEntity.badRequest().body(ErrorResponse.INVALID_REQUEST);
        }
        faults.apply(rule);
        return ResponseEntity.ok(rule);
    }

    @GetMapping
    List<FaultRule> current() {
        return faults.currentRules();
    }
}
