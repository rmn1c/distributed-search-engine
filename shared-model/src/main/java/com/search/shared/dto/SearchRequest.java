package com.search.shared.dto;

import java.util.List;
import java.util.Map;

public record SearchRequest(
    Map<String, Object> query,  // e.g. {"match":{"title":"hello"}}
    int size,
    int from,
    String pitId,               // null for non-PIT queries
    List<Object> searchAfter    // [score, _id] from last hit of previous page
) {
    // Compact canonical constructor normalises defaults
    public SearchRequest {
        if (size <= 0) size = 10;
        if (from < 0)  from = 0;
    }
}
