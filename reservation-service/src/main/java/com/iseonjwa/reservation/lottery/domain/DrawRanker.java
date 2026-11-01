package com.iseonjwa.reservation.lottery.domain;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 추첨 순위 계산(D7 C안).
 * <p>
 * 응모마다 {@code SHA-256(seed ‖ entryId)}를 구하고, 그 값을 오름차순으로 정렬한 것이 순위다.
 * 응모마다 값이 따로 정해지므로 다음이 성립한다.
 * <ul>
 *   <li>입력 순서와 관계없이 같은 시드·같은 응모면 같은 순위가 나온다. 추첨 중 실패해 다시 돌려도 결과가 같다.</li>
 *   <li>응모 하나가 추가·취소돼도 나머지 응모끼리의 앞뒤 순서는 바뀌지 않는다(셔플 방식인 D7 B안과의 차이).</li>
 *   <li>시드를 공개하면 누구나 자기 응모의 해시를 계산해 순위를 검증할 수 있다(시드 약속은 확장안 ISE-95).</li>
 * </ul>
 * 해시 입력 형식은 공개 계산식이므로 바꾸면 이미 진행한 추첨을 재현할 수 없다. 형식은 {@link #drawHash}에 있다.
 */
public final class DrawRanker {

    public static final int SEED_BYTES = 32;

    // SecureRandom은 스레드에 안전하므로 하나를 공유한다.
    private static final SecureRandom RANDOM = new SecureRandom();

    private DrawRanker() {}

    /**
     * 추첨 설정 때 한 번 만들어 저장하는 시드. 설정 뒤에는 바꾸지 못하게 엔티티에서 막는다(D7).
     */
    // Note: 예측할 수 있는 java.util.Random을 쓰면 시드를 추측해 순위를 미리 계산할 수 있다. 그래서 SecureRandom을 쓴다.
    public static byte[] newSeed() {
        var seed = new byte[SEED_BYTES];
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
        var digest = sha256();
        return entrants.stream()
                .map(entrant -> new Ranked(entrant, drawHash(digest, seed, entrant.entryId())))
                .sorted(Ranked.ORDER)
                .map(Ranked::entrant)
                .toList();
    }

    /**
     * 응모 하나의 순위 해시. 공개 계산식이며 외부 검증 도구도 같은 형식을 써야 한다.
     * <p>
     * 형식: {@code SHA-256(seed 바이트 그대로 ‖ entryId를 8바이트 big-endian으로)}.
     * 예를 들어 entryId 1은 {@code 00 00 00 00 00 00 00 01}로 붙인다.
     */
    public static byte[] drawHash(byte[] seed, long entryId) {
        return drawHash(sha256(), seed, entryId);
    }

    // Note: 숫자를 문자열("1")로 붙이면 언어마다 인코딩·자릿수 표현이 달라 같은 해시를 재현하기 어렵다.
    // 고정 길이 8바이트로 붙여 경계도 모호하지 않게 한다. digest()는 계산 뒤 내부 상태를 초기화하므로 같은 객체를 이어 써도 된다.
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

    // Note: Java의 byte는 -128~127이라 그대로 비교하면 0x80 이상인 바이트가 음수로 앞에 온다.
    // Python 등 외부 도구는 0~255로 비교하므로, 같은 순위를 재현하려면 부호 없이 비교해야 한다.
    // 해시가 같을 확률은 무시할 만하지만, 같을 때도 결과가 입력 순서에 기대지 않게 entryId로 한 번 더 가른다.
    private record Ranked(DrawEntrant entrant, byte[] hash) {
        static final Comparator<Ranked> ORDER = Comparator
                .comparing(Ranked::hash, Arrays::compareUnsigned)
                .thenComparingLong(ranked -> ranked.entrant().entryId());
    }
}
