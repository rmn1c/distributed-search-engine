package com.search.shared.dto;

import java.util.List;
import java.util.Map;

public record ShardSearchRequest(
    String indexName,
    int shardId,
    Map<String, Object> query,
    int size,
    String shardPitId,        // shard-local PIT ID (null for non-PIT queries)
    List<Object> searchAfter
) {}
