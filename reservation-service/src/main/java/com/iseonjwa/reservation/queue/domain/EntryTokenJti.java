package com.iseonjwa.reservation.queue.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class EntryTokenJti {

    private EntryTokenJti() {}

    public static String of(long scheduleId, long userId, long issuedAtEpochMillis) {
        String source = "entry:" + scheduleId + ":" + userId + ":" + issuedAtEpochMillis;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)); // 32바이트
            return HexFormat.of().formatHex(hash, 0, 16); // 앞 16바이트 -> 소문자 hex 32자
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
