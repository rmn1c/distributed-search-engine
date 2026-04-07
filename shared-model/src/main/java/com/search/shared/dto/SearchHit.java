package com.search.shared.dto;

import java.util.List;
import java.util.Map;

public record SearchHit(
    String id,
    float score,
    Map<String, Object> source,
    List<Object> sortValues   // [Float score, String _id] — used for search_after
) {}
