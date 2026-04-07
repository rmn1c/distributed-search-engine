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

        boolean hasPit = req.shardPitId() != null && !req.shardPitId().isBlank();
        int maxDoc = hasPit
            ? shard.getPITMaxDoc(req.shardPitId())
            : shard.getLiveMaxDoc();
        FieldDoc after = buildAfterDoc(req.searchAfter(), maxDoc);

        LuceneShard.ShardSearchResult result = hasPit
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
     *
     * IMPORTANT: FieldDoc.doc must satisfy after.doc < maxDoc (Lucene assertion).
     * We use maxDoc - 1 (the highest valid doc ID) so that the cursor document
     * itself — which has actual doc ID <= maxDoc - 1 — is never treated as "after"
     * the cursor. Since _id is globally unique, no two docs share the same sort
     * values, so the doc-ID tiebreaker is only reached for the exact cursor doc.
     */
    private FieldDoc buildAfterDoc(List<Object> sa, int maxDoc) {
        if (sa == null || sa.size() < 2) return null;
        float    score  = ((Number) sa.get(0)).floatValue();
        BytesRef idRef  = new BytesRef(sa.get(1).toString());
        // maxDoc == 0 means empty shard — no cursor needed; return null if no docs
        int      safeDoc = maxDoc > 0 ? maxDoc - 1 : 0;
        return new FieldDoc(safeDoc, score,
            new Object[]{ Float.valueOf(score), idRef });
    }
}
