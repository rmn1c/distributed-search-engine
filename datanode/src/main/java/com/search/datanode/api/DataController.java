package com.search.datanode.api;

import com.search.datanode.lucene.LuceneShard;
import com.search.datanode.lucene.ShardManager;
import com.search.shared.dto.IndexDocumentRequest;
import com.search.shared.dto.ShardSearchRequest;
import com.search.shared.dto.ShardSearchResponse;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.util.BytesRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DataController {

    private final ShardManager shardManager;

    public DataController(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    /** Primary write endpoint — called by coordinator for primary shard writes. */
    @PostMapping("/shards/{index}/{shardId}/documents")
    public ResponseEntity<Void> index(@PathVariable("index") String index,
                                       @PathVariable("shardId") int shardId,
                                       @RequestBody IndexDocumentRequest req) throws Exception {
        shardManager.getOrCreate(index, shardId).indexDocument(req.id(), req.source());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Shard-level search — called during the scatter phase.
     * Handles both live queries and PIT-backed queries.
     */
    @PostMapping("/shards/search")
    public ResponseEntity<ShardSearchResponse> search(@RequestBody ShardSearchRequest req)
            throws Exception {
        LuceneShard shard = shardManager.getOrThrow(req.indexName(), req.shardId());
        var query  = shard.buildQuery(req.query());
        FieldDoc after = buildAfterDoc(req.searchAfter());

        LuceneShard.ShardSearchResult result =
            (req.shardPitId() != null && !req.shardPitId().isBlank())
                ? shard.searchWithPIT(req.shardPitId(), query, req.size(), after)
                : shard.search(query, req.size(), after);

        return ResponseEntity.ok(new ShardSearchResponse(result.hits(), result.totalHits()));
    }

    /**
     * Reconstructs the Lucene FieldDoc "after" cursor from JSON sort values.
     *
     * Sort order = [Float score, String _id] matching LuceneShard.PAGINATION_SORT.
     * Lucene's searchAfter() uses FieldDoc.fields[] (not FieldDoc.score) for comparison:
     *   fields[0] = Float   (used by RelevanceComparator.setTopValue)
     *   fields[1] = BytesRef (used by TermValComparator.setTopValue)
     */
    private FieldDoc buildAfterDoc(List<Object> sa) {
        if (sa == null || sa.size() < 2) return null;
        float    score = ((Number) sa.get(0)).floatValue();
        BytesRef idRef = new BytesRef(sa.get(1).toString());
        // after.doc must be < shard's maxDoc or Lucene throws.
        // Since _id is unique across sort fields, the doc-ID tiebreaker is never
        // reached — any in-bounds value works. 0 is always valid for non-empty shards.
        return new FieldDoc(0, score,
            new Object[]{ Float.valueOf(score), idRef });
    }
}
