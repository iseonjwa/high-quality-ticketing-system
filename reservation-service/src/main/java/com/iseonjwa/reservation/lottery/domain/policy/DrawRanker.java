package com.iseonjwa.reservation.lottery.domain.policy;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 응모마다 {@code SHA-256(seed ‖ entryId)}를 구해 오름차순으로 정렬한 것을 추첨 순위로 삼는다(D7 C안).
 * <ul>
 *   <li>입력 순서와 관계없이 같은 시드·같은 응모면 같은 순위가 나온다. 추첨 중 실패해 다시 돌려도 결과가 같다.</li>
 *   <li>응모 하나가 추가·취소돼도 나머지 응모끼리의 앞뒤 순서는 바뀌지 않는다(셔플 방식인 D7 B안과의 차이).</li>
 *   <li>시드를 공개하면 누구나 자기 응모의 해시를 계산해 순위를 검증할 수 있다(시드 약속은 확장안 ISE-95).</li>
 *   <li>해시 입력 형식({@link #drawHash})은 공개 계산식이라 바꾸면 이미 진행한 추첨을 재현할 수 없다.</li>
 * </ul>
 */
public final class DrawRanker {

    public static final int SEED_BYTES = 32;

    // SecureRandom은 스레드에 안전하므로 하나를 공유한다.
    private static final SecureRandom RANDOM = new SecureRandom();

    private DrawRanker() {}

    /**
     * 추첨 설정 때 한 번 만들어 저장하는 시드로, 설정 뒤에는 엔티티에서 변경을 막는다(D7).
     */
    // Note: 시드를 추측해 순위를 미리 계산하지 못하도록 java.util.Random 대신 SecureRandom을 쓴다.
    public static byte[] newSeed() {
        byte[] seed = new byte[SEED_BYTES];
        RANDOM.nextBytes(seed);
        return seed;
    }

    /**
     * 응모를 순위 순(1위가 맨 앞)으로 정렬해 돌려준다. 입력 컬렉션은 바꾸지 않는다.
     */
    public static List<DrawEntrant> rank(byte[] seed, Collection<DrawEntrant> entrants) {
        if (seed == null || seed.length == 0) {
            throw new IllegalArgumentException("시드가 비어 있다");
        }
        // 정렬 비교 중에 해시를 매번 다시 계산하지 않도록 응모마다 한 번만 구해 함께 들고 정렬한다.
        MessageDigest digest = sha256();
        return entrants.stream()
                .map(entrant -> new Ranked(entrant, drawHash(digest, seed, entrant.entryId())))
                .sorted(Ranked.ORDER)
                .map(Ranked::entrant)
                .toList();
    }

    /**
     * 외부 검증 도구도 같은 형식을 써야 하는 공개 순위 해시로, 형식은
     * {@code SHA-256(seed 바이트 그대로 ‖ entryId 8바이트 big-endian)}이다(entryId 1은 {@code 00 00 00 00 00 00 00 01}).
     */
    public static byte[] drawHash(byte[] seed, long entryId) {
        return drawHash(sha256(), seed, entryId);
    }

    // Note: 언어마다 표현이 달라지는 숫자 문자열 대신 고정 길이 8바이트로 붙이고, digest()가 내부 상태를 초기화하므로 같은 객체를 이어 쓴다.
    private static byte[] drawHash(MessageDigest digest, byte[] seed, long entryId) {
        digest.update(seed);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entryId).array());
        return digest.digest();
    }

    // MessageDigest는 스레드에 안전하지 않으므로 공유하지 않고 호출마다 새로 만든다.
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK는 SHA-256을 항상 제공한다", e);
        }
    }

    // Note: 외부 도구와 같은 순위가 나오도록 해시를 부호 없는 바이트로 비교하고, 해시가 같으면 입력 순서 대신 entryId로 가른다.
    private record Ranked(DrawEntrant entrant, byte[] hash) {
        static final Comparator<Ranked> ORDER = Comparator
                .comparing(Ranked::hash, Arrays::compareUnsigned)
                .thenComparingLong(ranked -> ranked.entrant().entryId());
    }
}
