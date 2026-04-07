package com.search.shared.dto;

import java.util.List;

public record ShardSearchResponse(List<SearchHit> hits, long totalHits) {}
