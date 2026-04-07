package com.search.coordinator.index;

public record ShardAssignment(String nodeId, String nodeUrl, ShardRole role) {
    public enum ShardRole { PRIMARY, REPLICA }
}
