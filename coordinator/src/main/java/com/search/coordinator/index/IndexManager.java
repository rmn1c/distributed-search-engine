package com.search.coordinator.index;

import com.search.coordinator.registry.NodeInfo;
import com.search.coordinator.registry.NodeRegistry;
import com.search.shared.dto.CreateIndexRequest;
import com.search.shared.dto.ShardInitRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IndexManager {
    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final NodeRegistry nodeRegistry;
    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<String, IndexMeta> indices = new ConcurrentHashMap<>();

    public IndexManager(NodeRegistry nodeRegistry, RestTemplate restTemplate) {
        this.nodeRegistry = nodeRegistry;
        this.restTemplate = restTemplate;
    }

    /**
     * Creates a new index:
     * 1. Assigns shards to nodes using round-robin for balanced distribution.
     * 2. Assigns replicas to different nodes to ensure fault tolerance.
     * 3. Calls each data node to initialize Lucene shard directories.
     */
    public IndexMeta createIndex(CreateIndexRequest req) {
        if (indices.containsKey(req.indexName())) {
            throw new IllegalStateException("Index already exists: " + req.indexName());
        }

        List<NodeInfo> nodes = nodeRegistry.getAll();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No data nodes registered. Start at least one DataNode.");
        }
        // Sort by nodeId for deterministic, reproducible shard assignment
        nodes = nodes.stream().sorted(Comparator.comparing(NodeInfo::nodeId)).toList();

        IndexMeta meta = new IndexMeta(req.indexName(), req.shardCount(), req.replicaFactor());
        final List<NodeInfo> sortedNodes = nodes;

        for (int shardId = 0; shardId < req.shardCount(); shardId++) {
            // Primary: round-robin across nodes
            NodeInfo primaryNode = sortedNodes.get(shardId % sortedNodes.size());
            meta.addAssignment(shardId, new ShardAssignment(
                primaryNode.nodeId(), primaryNode.url(), ShardAssignment.ShardRole.PRIMARY));

            // Replicas: offset by (r+1) to guarantee different node from primary when possible
            for (int r = 0; r < req.replicaFactor(); r++) {
                int idx = (shardId + r + 1) % sortedNodes.size();
                NodeInfo replicaNode = sortedNodes.get(idx);
                meta.addAssignment(shardId, new ShardAssignment(
                    replicaNode.nodeId(), replicaNode.url(), ShardAssignment.ShardRole.REPLICA));
            }
        }

        // Initialise shards on data nodes (idempotent — uses CREATE_OR_APPEND)
        meta.getShardAssignments().forEach((shardId, assignments) ->
            assignments.forEach(a -> {
                try {
                    restTemplate.postForEntity(
                        a.nodeUrl() + "/internal/shards/init",
                        new ShardInitRequest(req.indexName(), shardId, a.role().name()),
                        Void.class);
                } catch (Exception e) {
                    log.error("Shard init failed for {}/{} on {}: {}",
                        req.indexName(), shardId, a.nodeUrl(), e.getMessage());
                }
            })
        );

        indices.put(req.indexName(), meta);
        log.info("Index created: {} ({} shards, {} replicas)",
            req.indexName(), req.shardCount(), req.replicaFactor());
        return meta;
    }

    public IndexMeta getIndex(String name) {
        return Optional.ofNullable(indices.get(name))
            .orElseThrow(() -> new NoSuchElementException("Index not found: " + name));
    }

    public boolean exists(String name) {
        return indices.containsKey(name);
    }

    public Map<String, IndexMeta> all() {
        return Collections.unmodifiableMap(indices);
    }
}
