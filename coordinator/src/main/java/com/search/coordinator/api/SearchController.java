package com.search.coordinator.api;

import com.search.coordinator.index.IndexManager;
import com.search.coordinator.index.IndexMeta;
import com.search.coordinator.merge.ResultMerger;
import com.search.coordinator.pit.PITManager;
import com.search.coordinator.query.QueryPlanner;
import com.search.coordinator.query.ScatterGatherExecutor;
import com.search.shared.dto.CreatePITRequest;
import com.search.shared.dto.CreatePITResponse;
import com.search.shared.dto.SearchRequest;
import com.search.shared.dto.SearchResponse;
import com.search.shared.dto.ShardSearchResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/indices")
public class SearchController {

    private final IndexManager indexManager;
    private final QueryPlanner planner;
    private final ScatterGatherExecutor scatterGather;
    private final ResultMerger merger;
    private final PITManager pitManager;

    public SearchController(IndexManager indexManager, QueryPlanner planner,
                            ScatterGatherExecutor scatterGather,
                            ResultMerger merger, PITManager pitManager) {
        this.indexManager = indexManager;
        this.planner      = planner;
        this.scatterGather = scatterGather;
        this.merger       = merger;
        this.pitManager   = pitManager;
    }

    @PostMapping("/{index}/search")
    public ResponseEntity<SearchResponse> search(
            @PathVariable String index,
            @RequestBody SearchRequest req) {

        IndexMeta meta = indexManager.getIndex(index);

        // Resolve global PIT -> per-shard PIT IDs
        Map<Integer, String> shardPitIds = null;
        if (req.pitId() != null) {
            PITManager.PITContext ctx = pitManager.get(req.pitId())
                .orElseThrow(() -> new NoSuchElementException(
                    "PIT expired or not found: " + req.pitId()));
            shardPitIds = new HashMap<>();
            for (var e : ctx.shardPITs().entrySet()) {
                shardPitIds.put(e.getKey(), e.getValue().shardPitId());
            }
        }

        // Plan -> Scatter -> Gather -> Merge
        var plans = planner.plan(req, meta, shardPitIds);
        List<ShardSearchResponse> responses = scatterGather.scatter(plans);
        var hits  = merger.merge(responses, req.size());
        long total = merger.totalHits(responses);

        return ResponseEntity.ok(new SearchResponse(hits, total, req.pitId()));
    }

    @PostMapping("/{index}/pit")
    public ResponseEntity<CreatePITResponse> createPIT(
            @PathVariable String index,
            @RequestBody CreatePITRequest req) {
        IndexMeta meta = indexManager.getIndex(index);
        Duration ttl   = parseTTL(req.keepAlive());
        PITManager.PITContext ctx = pitManager.open(meta, ttl);
        return ResponseEntity.ok(new CreatePITResponse(ctx.pitId(), ctx.expiresAtMs()));
    }

    @DeleteMapping("/pit/{pitId}")
    public ResponseEntity<Void> closePIT(@PathVariable String pitId) {
        pitManager.close(pitId);
        return ResponseEntity.noContent().build();
    }

    /** Parses "5m", "30s", "1h" into Duration. */
    private Duration parseTTL(String keepAlive) {
        if (keepAlive == null || keepAlive.isBlank()) return Duration.ofMinutes(5);
        char unit = keepAlive.charAt(keepAlive.length() - 1);
        long val  = Long.parseLong(keepAlive.substring(0, keepAlive.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(val);
            case 'm' -> Duration.ofMinutes(val);
            case 'h' -> Duration.ofHours(val);
            default  -> Duration.ofMinutes(5);
        };
    }
}
