package com.iseonjwa.mockpg.billing;

import com.iseonjwa.mockpg.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG 빌링키 발급(토스 빌링키 발급과 같은 흐름, ISE-96). 카드 등록 인증 값({@code authKey})을 받아 빌링키를 준다.
 * {@code authKey}가 {@code REJECT}로 시작하면 인증 실패(422 {@code INVALID_AUTH_KEY})로 답한다. 카드 끝 4자리는 고정값이다.
 */
@RestController
@RequestMapping("/mock-pg/billing-keys")
class BillingKeyController {
    static final String REJECT_PREFIX = "REJECT";
    private static final String CARD_LAST4 = "4242";

    private final BillingKeySigner signer;

    BillingKeyController(BillingKeySigner signer) {
        this.signer = signer;
    }

    @PostMapping
    ResponseEntity<?> issue(@RequestBody BillingKeyRequest request) {
        if (request.authKey().startsWith(REJECT_PREFIX)) {
            return ResponseEntity.unprocessableContent().body(new ErrorResponse("INVALID_AUTH_KEY"));
        }
        return ResponseEntity.ok(new Issued(signer.issue(), CARD_LAST4));
    }

    record Issued(String billingKey, String cardLast4) {}
}
