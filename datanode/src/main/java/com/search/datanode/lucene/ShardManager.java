package com.search.datanode.lucene;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and lifecycle manager for all LuceneShards on this node.
 * ShardManager is the single source of truth for shard existence.
 */
@Component
public class ShardManager {
    private static final Logger log = LoggerFactory.getLogger(ShardManager.class);

    private final Path storageRoot;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, LuceneShard> shards = new ConcurrentHashMap<>();

    public ShardManager(@Value("${node.storage.root:./data}") String storageRoot,
                        ObjectMapper objectMapper) {
        this.storageRoot  = Path.of(storageRoot);
        this.objectMapper = objectMapper;
    }

    /** Creates or returns an existing shard. Thread-safe via computeIfAbsent. */
    public LuceneShard getOrCreate(String indexName, int shardId) {
        return shards.computeIfAbsent(key(indexName, shardId), k -> {
            try {
                log.info("Creating Lucene shard {}/{}", indexName, shardId);
                return new LuceneShard(indexName, shardId, storageRoot, objectMapper);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot create shard " + k, ex);
            }
        });
    }

    public Optional<LuceneShard> get(String indexName, int shardId) {
        return Optional.ofNullable(shards.get(key(indexName, shardId)));
    }

    public LuceneShard getOrThrow(String indexName, int shardId) {
        return get(indexName, shardId)
            .orElseThrow(() -> new IllegalStateException(
                "Shard not initialized: " + key(indexName, shardId)));
    }

    public int shardCount() {
        return shards.size();
    }

    private String key(String idx, int shardId) {
        return idx + "/" + shardId;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} shards", shards.size());
        shards.forEach((k, shard) -> {
            try { shard.close(); }
            catch (IOException e) { log.error("Error closing shard {}: {}", k, e.getMessage()); }
        });
        shards.clear();
    }
}
