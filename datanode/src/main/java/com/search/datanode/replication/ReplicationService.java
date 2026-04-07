package com.search.datanode.replication;

import com.search.datanode.lucene.LuceneShard;
import com.search.datanode.lucene.ShardManager;
import com.search.shared.dto.IndexDocumentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies incoming replication payloads to replica shards.
 * Identical to primary indexing — both paths go through LuceneShard.indexDocument().
 */
@Service
public class ReplicationService {
    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final ShardManager shardManager;

    public ReplicationService(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    public void apply(String indexName, int shardId, IndexDocumentRequest req) {
        try {
            LuceneShard shard = shardManager.getOrThrow(indexName, shardId);
            shard.indexDocument(req.id(), req.source());
            log.debug("Replica applied doc '{}' -> {}/{}", req.id(), indexName, shardId);
        } catch (Exception e) {
            log.error("Replication error {}/{} doc '{}': {}",
                indexName, shardId, req.id(), e.getMessage());
            throw new RuntimeException("Replication failed", e);
        }
    }
}
