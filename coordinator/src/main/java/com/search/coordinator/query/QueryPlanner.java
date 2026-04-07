package com.search.coordinator.query;

import com.search.coordinator.index.IndexMeta;
import com.search.coordinator.index.ShardAssignment;
import com.search.shared.dto.SearchRequest;
import com.search.shared.dto.ShardSearchRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a coordinator-level SearchRequest into per-shard ShardQueryPlans.
 *
 * Current strategy: full broadcast to all shards (no shard pruning).
 * Production extension: parse routing keys from term queries to skip irrelevant shards,
 * reducing scatter fan-out for point queries (e.g., term on the routing field).
 */
@Component
public class QueryPlanner {

    /** Binds a shard request to the node URL it should be sent to. */
    public record ShardQueryPlan(String nodeUrl, ShardSearchRequest request) {}

    public List<ShardQueryPlan> plan(SearchRequest req, IndexMeta meta,
                                     Map<Integer, String> shardPitIds) {
        List<ShardQueryPlan> plans = new ArrayList<>(meta.getShardCount());

        for (int shardId = 0; shardId < meta.getShardCount(); shardId++) {
            final int sid = shardId;   // effectively-final capture for lambda
            // Use primary for reads (extend here for replica round-robin load balancing)
            ShardAssignment target = meta.getPrimary(shardId)
                .orElseThrow(() -> new IllegalStateException(
                    "No primary for shard " + sid + " in " + meta.getName()));

            String shardPitId = (shardPitIds != null) ? shardPitIds.get(shardId) : null;

            plans.add(new ShardQueryPlan(
                target.nodeUrl(),
                new ShardSearchRequest(
                    meta.getName(), shardId, req.query(),
                    req.size(),      // each shard returns up to `size` hits; merge picks top-K
                    shardPitId,
                    req.searchAfter()
                )
            ));
        }
        return plans;
    }
}
