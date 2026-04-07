package com.search.coordinator.pit;

import com.search.coordinator.index.IndexMeta;
import com.search.coordinator.index.ShardAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator-side Point-in-Time manager.
 *
 * A PIT provides a stable "snapshot" for deep pagination:
 * - Opened once, then reused across many search_after calls.
 * - Backed by pinned DirectoryReaders on each data node.
 * - Expires after TTL to reclaim reader resources.
 *
 * Global PIT ID -> per-shard PIT IDs stored on each DataNode.
 * Actual heap/file-handle cost is on the DataNode (pinned IndexReader).
 */
@Component
public class PITManager {
    private static final Logger log = LoggerFactory.getLogger(PITManager.class);

    /** Per-shard PIT handle. */
    public record ShardPITInfo(String nodeUrl, String shardPitId) {}

    /** Full PIT context stored in coordinator memory. */
    public record PITContext(
        String pitId,
        String indexName,
        Map<Integer, ShardPITInfo> shardPITs,
        long expiresAtMs
    ) {}

    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<String, PITContext> activePITs = new ConcurrentHashMap<>();

    public PITManager(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Opens a PIT by asking each primary shard to snapshot its IndexReader.
     * The snapshot is taken at the last committed segment — subsequent writes
     * are invisible to this PIT, ensuring stable ordering for deep pagination.
     */
    public PITContext open(IndexMeta indexMeta, Duration ttl) {
        String pitId = UUID.randomUUID().toString();
        Map<Integer, ShardPITInfo> shardPITs = new HashMap<>();

        for (int shardId = 0; shardId < indexMeta.getShardCount(); shardId++) {
            ShardAssignment primary = indexMeta.getPrimary(shardId)
                .orElseThrow(() -> new IllegalStateException("No primary for shard " + shardId));
            try {
                // DataNode opens DirectoryReader snapshot, returns shard-local PIT ID
                String shardPitId = restTemplate.postForObject(
                    primary.nodeUrl() + "/internal/shards/"
                        + indexMeta.getName() + "/" + shardId + "/pit",
                    null, String.class);
                shardPITs.put(shardId, new ShardPITInfo(primary.nodeUrl(), shardPitId));
                log.debug("Opened shard PIT {} on {}/{}", shardPitId, indexMeta.getName(), shardId);
            } catch (Exception e) {
                log.error("Failed to open PIT on shard {}/{}: {}",
                    indexMeta.getName(), shardId, e.getMessage());
            }
        }

        PITContext ctx = new PITContext(pitId, indexMeta.getName(), shardPITs,
            System.currentTimeMillis() + ttl.toMillis());
        activePITs.put(pitId, ctx);
        log.info("PIT {} opened for index {} ({} shard snapshots)",
            pitId, indexMeta.getName(), shardPITs.size());
        return ctx;
    }

    public Optional<PITContext> get(String pitId) {
        PITContext ctx = activePITs.get(pitId);
        if (ctx == null) return Optional.empty();
        if (System.currentTimeMillis() > ctx.expiresAtMs()) {
            close(pitId);   // lazy expiry on access
            return Optional.empty();
        }
        return Optional.of(ctx);
    }

    public void close(String pitId) {
        PITContext ctx = activePITs.remove(pitId);
        if (ctx == null) return;

        // Release all shard-level readers on data nodes
        ctx.shardPITs().forEach((shardId, info) -> {
            try {
                restTemplate.delete(
                    info.nodeUrl() + "/internal/shards/"
                        + ctx.indexName() + "/" + shardId + "/pit/" + info.shardPitId());
            } catch (Exception e) {
                log.warn("Failed to close shard PIT {}: {}", info.shardPitId(), e.getMessage());
            }
        });
        log.info("PIT {} closed", pitId);
    }

    /** Background sweep: evict PITs that have passed their TTL. */
    @Scheduled(fixedRate = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = activePITs.entrySet().stream()
            .filter(e -> e.getValue().expiresAtMs() < now)
            .map(Map.Entry::getKey)
            .toList();
        expired.forEach(id -> {
            log.info("Evicting expired PIT {}", id);
            close(id);
        });
    }

    public Map<String, PITContext> activePITs() {
        return Collections.unmodifiableMap(activePITs);
    }
}
