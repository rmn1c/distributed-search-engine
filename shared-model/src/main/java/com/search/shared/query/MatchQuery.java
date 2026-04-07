package com.search.shared.query;

public record MatchQuery(String field, String value) implements QueryClause {}
