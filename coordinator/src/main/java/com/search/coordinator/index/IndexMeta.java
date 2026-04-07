package com.search.coordinator.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory routing table for one index.
 * shardAssignments: shardId -> [primary, replica0, replica1, ...]
 *
 * Designed to be swapped for a distributed KV store (etcd, ZooKeeper)
 * without changing coordinator logic — extract an IndexMetaStore interface.
 */
public class IndexMeta {
    private final String name;
    private final int shardCount;
    private final int replicaFactor;
    private final long createdAt = System.currentTimeMillis();
    private final Map<Integer, List<ShardAssignment>> shardAssignments = new ConcurrentHashMap<>();

    public IndexMeta(String name, int shardCount, int replicaFactor) {
        this.name = name;
        this.shardCount = shardCount;
        this.replicaFactor = replicaFactor;
    }

    public void addAssignment(int shardId, ShardAssignment assignment) {
        shardAssignments.computeIfAbsent(shardId, k -> new ArrayList<>()).add(assignment);
    }

    public Optional<ShardAssignment> getPrimary(int shardId) {
        return shardAssignments.getOrDefault(shardId, List.of()).stream()
            .filter(a -> a.role() == ShardAssignment.ShardRole.PRIMARY)
            .findFirst();
    }

    public List<ShardAssignment> getReplicas(int shardId) {
        return shardAssignments.getOrDefault(shardId, List.of()).stream()
            .filter(a -> a.role() == ShardAssignment.ShardRole.REPLICA)
            .toList();
    }

    public String getName()       { return name; }
    public int getShardCount()    { return shardCount; }
    public int getReplicaFactor() { return replicaFactor; }
    public long getCreatedAt()    { return createdAt; }

    public Map<Integer, List<ShardAssignment>> getShardAssignments() {
        return Collections.unmodifiableMap(shardAssignments);
    }
}
