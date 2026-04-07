package com.search.shared.query;

public record TermQuery(String field, String value) implements QueryClause {}
