package com.search.shared.dto;

import java.util.List;

public record SearchResponse(List<SearchHit> hits, long total, String pitId) {}
