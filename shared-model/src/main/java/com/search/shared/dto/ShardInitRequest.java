package com.search.shared.dto;

public record ShardInitRequest(String indexName, int shardId, String role) {}
