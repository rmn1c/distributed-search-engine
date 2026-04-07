package com.search.datanode.health;

import com.search.datanode.lucene.ShardManager;
import org.springframework.stereotype.Component;

/**
 * Reports node health status for coordinator health checks.
 */
@Component
public class NodeHealthTracker {

    private final ShardManager shardManager;
    private final long startTime = System.currentTimeMillis();

    public NodeHealthTracker(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    public record HealthStatus(String status, long uptimeMs, int shardCount) {}

    public HealthStatus getStatus() {
        return new HealthStatus("UP",
            System.currentTimeMillis() - startTime,
            shardManager.shardCount());
    }
}
