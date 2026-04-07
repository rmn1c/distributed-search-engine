package com.search.datanode.api;

import com.search.datanode.health.NodeHealthTracker;
import com.search.datanode.lucene.LuceneShard;
import com.search.datanode.lucene.ShardManager;
import com.search.datanode.replication.ReplicationService;
import com.search.shared.dto.IndexDocumentRequest;
import com.search.shared.dto.ShardInitRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API — coordinator <-> datanode communication.
 * In production, protect with mTLS or a network-level policy.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ShardManager shardManager;
    private final ReplicationService replication;
    private final NodeHealthTracker healthTracker;

    public InternalController(ShardManager shardManager, ReplicationService replication,
                               NodeHealthTracker healthTracker) {
        this.shardManager  = shardManager;
        this.replication   = replication;
        this.healthTracker = healthTracker;
    }

    /** Initialise a shard directory. Idempotent (CREATE_OR_APPEND). */
    @PostMapping("/shards/init")
    public ResponseEntity<Void> initShard(@RequestBody ShardInitRequest req) {
        shardManager.getOrCreate(req.indexName(), req.shardId());
        return ResponseEntity.ok().build();
    }

    /** Push-replication endpoint: coordinator sends doc after primary commits. */
    @PostMapping("/shards/{index}/{shardId}/index")
    public ResponseEntity<Void> replicate(@PathVariable String index,
                                           @PathVariable int shardId,
                                           @RequestBody IndexDocumentRequest req) {
        replication.apply(index, shardId, req);
        return ResponseEntity.ok().build();
    }

    /**
     * Opens a PIT snapshot on a specific shard.
     * Returns the shard-local pitId that the coordinator stores in PITContext.shardPITs.
     */
    @PostMapping("/shards/{index}/{shardId}/pit")
    public ResponseEntity<String> openPIT(@PathVariable String index,
                                           @PathVariable int shardId) throws Exception {
        LuceneShard shard = shardManager.getOrThrow(index, shardId);
        return ResponseEntity.ok(shard.openPIT());
    }

    /** Closes and releases a PIT snapshot — frees the pinned DirectoryReader. */
    @DeleteMapping("/shards/{index}/{shardId}/pit/{pitId}")
    public ResponseEntity<Void> closePIT(@PathVariable String index,
                                          @PathVariable int shardId,
                                          @PathVariable String pitId) throws Exception {
        shardManager.getOrThrow(index, shardId).closePIT(pitId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public ResponseEntity<NodeHealthTracker.HealthStatus> health() {
        return ResponseEntity.ok(healthTracker.getStatus());
    }
}
