package com.search.shared.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateIndexRequest(
    @NotBlank String indexName,
    @Min(1)   int shardCount,
    @Min(0)   int replicaFactor
) {}
