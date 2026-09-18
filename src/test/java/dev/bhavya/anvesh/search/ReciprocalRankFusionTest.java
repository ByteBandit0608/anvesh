package dev.bhavya.anvesh.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private static SearchHit hit(long id, double score) {
        return new SearchHit(id, UUID.nameUUIDFromBytes(("d" + id).getBytes()), "t" + id, "s" + id, score);
    }

    @Test
    void itemPresentInBothListsOutranksSingleListWinner() {
        List<SearchHit> vector = List.of(hit(1, 0.9), hit(2, 0.8), hit(3, 0.7));
        List<SearchHit> keyword = List.of(hit(9, 5.0), hit(2, 4.0), hit(1, 3.0));
        List<SearchHit> fused = ReciprocalRankFusion.fuse(60, 10, vector, keyword);

        // 1 and 2 appear in both lists; 9 and 3 only in one.
        assertThat(fused).extracting(SearchHit::chunkId).startsWith(1L, 2L);
        assertThat(fused).extracting(SearchHit::chunkId).containsExactlyInAnyOrder(1L, 2L, 3L, 9L);
    }

    @Test
    void scoresFollowTheFormula() {
        List<SearchHit> fused = ReciprocalRankFusion.fuse(60, 10, List.of(hit(1, 0)), List.of(hit(1, 0)));
        assertThat(fused.get(0).score()).isCloseTo(2.0 / 61, within(1e-9));
    }

    @Test
    void respectsLimit() {
        List<SearchHit> many = List.of(hit(1, 0), hit(2, 0), hit(3, 0), hit(4, 0));
        assertThat(ReciprocalRankFusion.fuse(60, 2, many)).hasSize(2);
    }

    @Test
    void ignoresRawScoreScale() {
        // Same ranks, wildly different raw scores -> identical fusion output.
        List<SearchHit> a = List.of(hit(1, 0.99), hit(2, 0.01));
        List<SearchHit> b = List.of(hit(1, 1e6), hit(2, 1e5));
        assertThat(ReciprocalRankFusion.fuse(60, 5, a).stream().map(SearchHit::score).toList())
                .isEqualTo(ReciprocalRankFusion.fuse(60, 5, b).stream().map(SearchHit::score).toList());
    }
}
