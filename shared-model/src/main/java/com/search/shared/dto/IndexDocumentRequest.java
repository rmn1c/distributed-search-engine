package com.search.shared.dto;

import java.util.Map;

public record IndexDocumentRequest(
    String id,                 // nullable; coordinator assigns UUID if absent
    Map<String, Object> source // arbitrary JSON document
) {}
