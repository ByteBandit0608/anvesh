package dev.bhavya.anvesh.search;

import java.util.*;

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009).
 *
 * <p>score(d) = Σ over rankings r of 1 / (k + rank_r(d)).
 * It only looks at *ranks*, never raw scores — which is why it can merge a cosine similarity
 * list (0..1) with a ts_rank list (unbounded) without any calibration. k=60 is the paper's
 * default and damps the advantage of being #1 in a single list.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {}

    @SafeVarargs
    public static List<SearchHit> fuse(int k, int limit, List<SearchHit>... rankings) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, SearchHit> hits = new LinkedHashMap<>();

        for (List<SearchHit> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                SearchHit hit = ranking.get(rank);
                scores.merge(hit.chunkId(), 1.0 / (k + rank + 1), Double::sum);
                hits.putIfAbsent(hit.chunkId(), hit);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> hits.get(e.getKey()).withScore(e.getValue()))
                .toList();
    }
}
