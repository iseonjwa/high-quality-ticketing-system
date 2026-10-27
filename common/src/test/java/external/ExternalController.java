package external;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExternalController {
    @GetMapping("/actuator/sample")
    String metrics() {
        return "metric 1";
    }

    @GetMapping("/mock-pg/failure")
    void failure() {
        throw new IllegalStateException("외부 컨트롤러 예외");
    }
}
