package com.iseonjwa.reservation.lottery.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawRankerTest {

    // 0x00~0x1f 32바이트. 기대값은 Python hashlib으로 따로 계산했다:
    // sorted(ids, key=lambda i: sha256(seed + struct.pack('>q', i)).digest())
    private static final byte[] SEED = HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    @Test
    void 공개한_계산식과_같은_순위가_나온다() {
        var ranked = DrawRanker.rank(SEED, entrants(1, 8));

        // 첫 바이트 28, 56, 96, 115, 118, 176, 195, 225 순서. 0x80 이상 값이 섞여 있어 부호 있는 비교면 순서가 바뀐다.
        assertThat(ids(ranked)).containsExactly(6L, 3L, 1L, 2L, 4L, 5L, 8L, 7L);
    }

    @Test
    void 응모_해시는_시드_뒤에_8바이트_big_endian_응모_ID를_붙여_구한다() {
        assertThat(HexFormat.of().formatHex(DrawRanker.drawHash(SEED, 1L)))
                .isEqualTo("6061c4386d7a1788ba52e2e8b2ee6fe6137644ec75a70bf7042cfd67a1e57bd3");
    }

    @Test
    void 입력_순서를_섞어도_같은_순위가_나온다() {
        var entrants = new ArrayList<>(entrants(1, 500));
        var expected = ids(DrawRanker.rank(SEED, entrants));

        Collections.shuffle(entrants, new Random(42));

        assertThat(ids(DrawRanker.rank(SEED, entrants))).isEqualTo(expected);
    }

    @Test
    void 시드가_다르면_순위가_달라진다() {
        var entrants = entrants(1, 50);
        var otherSeed = SEED.clone();
        otherSeed[0] = 1;

        assertThat(ids(DrawRanker.rank(otherSeed, entrants))).isNotEqualTo(ids(DrawRanker.rank(SEED, entrants)));
    }

    @Test
    void 새_시드는_32바이트이고_매번_다르다() {
        assertThat(DrawRanker.newSeed()).hasSize(DrawRanker.SEED_BYTES).isNotEqualTo(DrawRanker.newSeed());
    }

    @Test
    void 빈_시드는_거절한다() {
        assertThatThrownBy(() -> DrawRanker.rank(new byte[0], entrants(1, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<DrawEntrant> entrants(long fromId, long toId) {
        return LongStream.rangeClosed(fromId, toId).mapToObj(id -> new DrawEntrant(id, 1)).toList();
    }

    private static List<Long> ids(List<DrawEntrant> entrants) {
        return entrants.stream().map(DrawEntrant::entryId).toList();
    }
}
