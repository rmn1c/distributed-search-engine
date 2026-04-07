package com.search.coordinator.api;

import com.search.coordinator.index.IndexManager;
import com.search.coordinator.index.IndexMeta;
import com.search.coordinator.replication.ReplicationOrchestrator;
import com.search.coordinator.routing.ShardRouter;
import com.search.shared.dto.CreateIndexRequest;
import com.search.shared.dto.IndexDocumentRequest;
import com.search.shared.dto.IndexDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/indices")
public class IndexController {

    private final IndexManager indexManager;
    private final ShardRouter shardRouter;
    private final ReplicationOrchestrator replication;
    private final RestTemplate restTemplate;

    public IndexController(IndexManager indexManager, ShardRouter shardRouter,
                           ReplicationOrchestrator replication, RestTemplate restTemplate) {
        this.indexManager = indexManager;
        this.shardRouter  = shardRouter;
        this.replication  = replication;
        this.restTemplate = restTemplate;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createIndex(
            @Validated @RequestBody CreateIndexRequest req) {
        IndexMeta meta = indexManager.createIndex(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "index",         meta.getName(),
            "shardCount",    meta.getShardCount(),
            "replicaFactor", meta.getReplicaFactor(),
            "acknowledged",  true
        ));
    }

    @PostMapping("/{index}/documents")
    public ResponseEntity<IndexDocumentResponse> indexDocument(
            @PathVariable String index,
            @RequestBody IndexDocumentRequest req) {

        IndexMeta meta = indexManager.getIndex(index);

        // Assign _id if absent — UUID preserves client control while enabling auto-ID
        String docId = (req.id() != null && !req.id().isBlank())
            ? req.id() : UUID.randomUUID().toString();

        int shardId = shardRouter.route(docId, meta.getShardCount());
        var primary = meta.getPrimary(shardId)
            .orElseThrow(() -> new IllegalStateException("No primary for shard " + shardId));

        IndexDocumentRequest withId = new IndexDocumentRequest(docId, req.source());

        // Write to primary
        restTemplate.postForEntity(
            primary.nodeUrl() + "/shards/" + index + "/" + shardId + "/documents",
            withId, Void.class);

        // Replicate to replicas (synchronous push)
        replication.replicate(meta, shardId, withId);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new IndexDocumentResponse(docId, shardId, primary.nodeUrl()));
    }

    @GetMapping("/{index}")
    public ResponseEntity<Map<String, Object>> getIndex(@PathVariable String index) {
        IndexMeta meta = indexManager.getIndex(index);
        return ResponseEntity.ok(Map.of(
            "index",         meta.getName(),
            "shardCount",    meta.getShardCount(),
            "replicaFactor", meta.getReplicaFactor(),
            "createdAt",     meta.getCreatedAt()
        ));
    }

    @GetMapping
    public ResponseEntity<Object> listIndices() {
        return ResponseEntity.ok(indexManager.all().keySet());
    }
}
