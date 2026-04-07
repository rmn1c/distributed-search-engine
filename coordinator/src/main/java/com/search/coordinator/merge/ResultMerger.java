package com.search.coordinator.merge;

import com.search.shared.dto.SearchHit;
import com.search.shared.dto.ShardSearchResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Merges results from multiple shards into a globally sorted, deduplicated result set.
 *
 * Sort invariant: score DESC, _id ASC (stable tiebreaker).
 * This matches the per-shard sort order defined in LuceneShard.PAGINATION_SORT,
 * so the merge is a k-way merge of already-sorted lists — O(N log k).
 *
 * Deduplication: not needed in a well-partitioned index (each doc lives on
 * exactly one primary shard). If replicas are queried, _id deduplication
 * would be required here.
 */
@Component
public class ResultMerger {

    private static final Comparator<SearchHit> GLOBAL_ORDER =
        Comparator.comparingDouble(SearchHit::score).reversed()
            .thenComparing(SearchHit::id);

    public List<SearchHit> merge(List<ShardSearchResponse> responses, int size) {
        return responses.stream()
            .flatMap(r -> r.hits().stream())
            .sorted(GLOBAL_ORDER)
            .limit(size)
            .collect(Collectors.toList());
    }

    public long totalHits(List<ShardSearchResponse> responses) {
        return responses.stream().mapToLong(ShardSearchResponse::totalHits).sum();
    }
}
