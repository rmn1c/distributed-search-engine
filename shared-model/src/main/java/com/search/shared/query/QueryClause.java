package com.search.shared.query;

import java.util.Map;

/**
 * Sealed query DSL hierarchy.
 * Pattern matching (Java 21) lets callers exhaustively switch on query type
 * without casting. New query types simply add a new permit clause.
 */
public sealed interface QueryClause permits MatchQuery, TermQuery, MatchAllQuery {

    @SuppressWarnings("unchecked")
    static QueryClause fromMap(Map<String, Object> raw) {
        if (raw.containsKey("match")) {
            var m = (Map<String, Object>) raw.get("match");
            var e = m.entrySet().iterator().next();
            return new MatchQuery(e.getKey(), e.getValue().toString());
        }
        if (raw.containsKey("term")) {
            var t = (Map<String, Object>) raw.get("term");
            var e = t.entrySet().iterator().next();
            return new TermQuery(e.getKey(), e.getValue().toString());
        }
        if (raw.containsKey("match_all")) {
            return new MatchAllQuery();
        }
        throw new IllegalArgumentException("Unsupported query clause: " + raw.keySet());
    }
}
