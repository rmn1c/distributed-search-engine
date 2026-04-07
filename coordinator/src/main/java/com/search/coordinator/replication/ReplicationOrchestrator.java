package com.search.coordinator.replication;

import com.search.coordinator.index.IndexMeta;
import com.search.coordinator.index.ShardAssignment;
import com.search.shared.dto.IndexDocumentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Orchestrates synchronous push replication from primary to replicas.
 *
 * After the primary commits a document, the coordinator pushes the same
 * document to every replica shard. This is "coordinator-mediated replication".
 *
 * Trade-off: synchronous replication adds write latency proportional to replica
 * count and network RTT. For higher write throughput, switch to async replication
 * (fire-and-forget) accepting eventual consistency on replicas.
 *
 * Failure handling: per-replica errors are logged but do not fail the overall
 * write. Stale replicas should be recovered via a separate sync mechanism.
 */
@Component
public class ReplicationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ReplicationOrchestrator.class);

    private final RestTemplate restTemplate;

    public ReplicationOrchestrator(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void replicate(IndexMeta meta, int shardId, IndexDocumentRequest doc) {
        for (ShardAssignment replica : meta.getReplicas(shardId)) {
            try {
                restTemplate.postForEntity(
                    replica.nodeUrl() + "/internal/shards/"
                        + meta.getName() + "/" + shardId + "/index",
                    doc, Void.class);
                log.debug("Replicated doc '{}' → replica {} shard {}",
                    doc.id(), replica.nodeUrl(), shardId);
            } catch (Exception e) {
                log.error("Replication failed → replica {} shard {}: {}",
                    replica.nodeUrl(), shardId, e.getMessage());
                // Mark replica stale in a production system; trigger recovery
            }
        }
    }
}
