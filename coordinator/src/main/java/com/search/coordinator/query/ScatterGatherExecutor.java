package com.search.coordinator.query;

import com.search.shared.dto.ShardSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Parallel scatter-gather over all target shards.
 *
 * Uses Java 21 virtual threads: each HTTP call parks on I/O without
 * consuming a platform thread. With 100 shards, 100 virtual threads are
 * created — effectively free compared to 100 blocking OS threads.
 *
 * Failure model: per-shard failures return empty results (partial degradation).
 * A production system would expose _shards.failed / _shards.total counters.
 */
@Component
public class ScatterGatherExecutor {
    private static final Logger log = LoggerFactory.getLogger(ScatterGatherExecutor.class);
    private static final int SHARD_TIMEOUT_SECONDS = 10;

    private final RestTemplate restTemplate;
    private final ExecutorService executor;   // virtual-thread executor from AppConfig

    public ScatterGatherExecutor(RestTemplate restTemplate,
                                  @Qualifier("scatterGatherExecutor") ExecutorService executor) {
        this.restTemplate = restTemplate;
        this.executor = executor;
    }

    public List<ShardSearchResponse> scatter(List<QueryPlanner.ShardQueryPlan> plans) {
        // Fan out: one CompletableFuture per shard, each running on a virtual thread
        List<CompletableFuture<ShardSearchResponse>> futures = plans.stream()
            .map(plan -> CompletableFuture.supplyAsync(() -> query(plan), executor))
            .toList();

        // Gather: wait for all, collect successes and partial failures
        return futures.stream()
            .map(f -> {
                try {
                    return f.get(SHARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("Shard query timed out after {}s", SHARD_TIMEOUT_SECONDS);
                    return emptyResponse();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Shard query failed: {}", e.getCause() != null
                        ? e.getCause().getMessage() : e.getMessage());
                    return emptyResponse();
                }
            })
            .collect(Collectors.toList());
    }

    private ShardSearchResponse query(QueryPlanner.ShardQueryPlan plan) {
        try {
            ShardSearchResponse resp = restTemplate.postForObject(
                plan.nodeUrl() + "/shards/search",
                plan.request(),
                ShardSearchResponse.class
            );
            return resp != null ? resp : emptyResponse();
        } catch (Exception e) {
            log.error("Scatter failed for shard {} on {}: {}",
                plan.request().shardId(), plan.nodeUrl(), e.getMessage());
            return emptyResponse();
        }
    }

    private static ShardSearchResponse emptyResponse() {
        return new ShardSearchResponse(List.of(), 0L);
    }
}
