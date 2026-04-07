package com.search.shared.dto;

public record IndexDocumentResponse(String id, int shardId, String primaryNodeUrl) {}
