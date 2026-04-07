package com.search.coordinator.routing;

import org.springframework.stereotype.Component;

/**
 * Deterministic document-to-shard routing using FNV-1a hash.
 *
 * FNV-1a over String.hashCode() for better avalanche properties:
 * nearby string values (e.g., "user-1", "user-2") map to well-dispersed shards,
 * preventing hot-shard skew.
 *
 * Trade-off: simple modulo hashing is not "consistent" in the ring sense —
 * shard count changes require full re-routing. For a production system,
 * implement virtual-node consistent hashing to minimise reshuffling.
 */
@Component
public class ShardRouter {

    public int route(String documentId, int shardCount) {
        return (int) (Math.abs(fnv1a(documentId)) % shardCount);
    }

    private long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;   // FNV-1a 64-bit offset basis
        for (char c : s.toCharArray()) {
            h ^= c;
            h *= 0x100000001b3L;        // FNV prime
        }
        return h;
    }
}
