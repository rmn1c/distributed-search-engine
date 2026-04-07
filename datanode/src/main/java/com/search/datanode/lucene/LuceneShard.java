package com.search.datanode.lucene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.search.shared.dto.SearchHit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe wrapper around a single Lucene index shard.
 *
 * ─── Thread Safety ──────────────────────────────────────────────────────────
 * IndexWriter     → thread-safe for concurrent addDocument / updateDocument
 * SearcherManager → thread-safe acquire/release pattern for concurrent reads
 * PIT readers     → each DirectoryReader snapshot is immutable after open;
 *                   ConcurrentHashMap guards the pitId -> reader registry
 *
 * ─── Resource Lifecycle ─────────────────────────────────────────────────────
 * Close order matters: SearcherManager -> IndexWriter -> Directory
 * Closing SearcherManager releases its internally held IndexSearcher.
 * Closing IndexWriter flushes pending segments and frees write lock.
 * Closing Directory closes all OS file handles.
 *
 * ─── Dynamic Field Mapping ──────────────────────────────────────────────────
 * Strings   -> TextField  (analyzed, for full-text match queries)
 *           +  StringField (not analyzed, for exact term queries via .keyword)
 *           +  SortedDocValuesField (enables sorting/faceting)
 * Numerics  -> XxxPoint   (for range queries)
 *           +  NumericDocValuesField (for sorting/aggregation)
 * Nested    -> flattened with dot-notation keys
 */
public class LuceneShard implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(LuceneShard.class);

    /**
     * Pagination sort: score descending (primary), _id ascending (stable tiebreaker).
     * Both fields must be present in EVERY document via DocValues — enforced in indexDocument().
     * This sort is also used on each shard, making the coordinator's ResultMerger a simple
     * k-way merge of already-sorted lists.
     */
    static final Sort PAGINATION_SORT = new Sort(
        SortField.FIELD_SCORE,                         // primary: relevance desc
        new SortField("_id", SortField.Type.STRING)   // tiebreaker: _id asc
    );

    private final String indexName;
    private final int shardId;
    private final Directory directory;
    private final StandardAnalyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final ObjectMapper objectMapper;

    /** PIT snapshot registry: pitId -> pinned DirectoryReader (never refreshed). */
    private final ConcurrentHashMap<String, DirectoryReader> pitReaders = new ConcurrentHashMap<>();

    public LuceneShard(String indexName, int shardId,
                       Path storageRoot, ObjectMapper objectMapper) throws IOException {
        this.indexName    = indexName;
        this.shardId      = shardId;
        this.objectMapper = objectMapper;

        Path shardDir = storageRoot.resolve(indexName).resolve("shard_" + shardId);
        Files.createDirectories(shardDir);

        this.directory = FSDirectory.open(shardDir);
        this.analyzer  = new StandardAnalyzer();

        // CREATE_OR_APPEND: idempotent — safe to call even if shard already exists on disk
        this.writer = new IndexWriter(directory,
            new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setCommitOnClose(true));

        // SearcherManager wraps the IndexWriter for near-real-time reads.
        // Passing the writer (not directory) means new docs are visible without a full flush.
        this.searcherManager = new SearcherManager(writer, null);
    }

    // =========================================================================
    // Write path
    // =========================================================================

    /**
     * Upserts a document: if _id already exists it is atomically replaced.
     * Commits after each write for durability; production would batch commits.
     */
    public void indexDocument(String id, Map<String, Object> source) throws IOException {
        Document doc = new Document();

        // _id: keyword (routing + dedup) + DocValues (sort in pagination)
        doc.add(new StringField("_id", id, Field.Store.YES));
        doc.add(new SortedDocValuesField("_id", new BytesRef(id)));

        // Full source stored for _source retrieval
        doc.add(new StoredField("_source", objectMapper.writeValueAsString(source)));

        // Dynamic field indexing
        source.forEach((field, value) -> addField(doc, field, value));

        // Atomic upsert: delete-then-add on the unique _id term
        writer.updateDocument(new Term("_id", id), doc);
        writer.commit();

        // Notify SearcherManager so subsequent searches see this document
        searcherManager.maybeRefresh();
        log.debug("Indexed doc '{}' -> shard {}/{}", id, indexName, shardId);
    }

    @SuppressWarnings("unchecked")
    private void addField(Document doc, String field, Object value) {
        if (value == null) return;
        switch (value) {
            case String s -> {
                doc.add(new TextField(field, s, Field.Store.NO));                  // full-text
                doc.add(new StringField(field + ".keyword", s, Field.Store.NO));   // exact
                doc.add(new SortedDocValuesField(field + ".keyword", new BytesRef(s))); // sort
            }
            case Integer i -> {
                doc.add(new IntPoint(field, i));
                doc.add(new NumericDocValuesField(field, i));
                doc.add(new StoredField(field, i));
            }
            case Long l -> {
                doc.add(new LongPoint(field, l));
                doc.add(new NumericDocValuesField(field, l));
                doc.add(new StoredField(field, l));
            }
            case Double d -> {
                doc.add(new DoublePoint(field, d));
                doc.add(new DoubleDocValuesField(field, d));
                doc.add(new StoredField(field, d));
            }
            case Boolean b ->
                doc.add(new StringField(field, b.toString(), Field.Store.YES));
            case Map<?, ?> nested ->
                ((Map<String, Object>) nested).forEach((k, v) ->
                    addField(doc, field + "." + k, v));  // flatten with dot-notation
            default ->
                doc.add(new StringField(field, value.toString(), Field.Store.NO));
        }
    }

    // =========================================================================
    // Read path — live queries (no PIT)
    // =========================================================================

    public record ShardSearchResult(List<SearchHit> hits, long totalHits) {}

    /**
     * Executes a query against the live SearcherManager.
     * The acquire/release pattern is mandatory: releasing returns the searcher
     * to the internal pool so it can be refreshed and GC'd properly.
     */
    public ShardSearchResult search(Query query, int size, FieldDoc searchAfter) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return execute(searcher, query, size, searchAfter);
        } finally {
            searcherManager.release(searcher);   // ALWAYS release — even on exception
        }
    }

    // =========================================================================
    // Read path — PIT queries (stable snapshot)
    // =========================================================================

    /**
     * Executes a query against a pinned PIT snapshot.
     * The reader is intentionally NOT refreshed — writes after PIT creation
     * are invisible, providing the stable ordering needed for deep pagination.
     */
    public ShardSearchResult searchWithPIT(String pitId, Query query,
                                            int size, FieldDoc searchAfter) throws IOException {
        DirectoryReader pitReader = pitReaders.get(pitId);
        if (pitReader == null) {
            throw new IllegalArgumentException("PIT not found or expired: " + pitId);
        }
        // Construct a fresh IndexSearcher over the pinned reader.
        // No acquire/release needed — the reader lifecycle is managed by PIT.
        return execute(new IndexSearcher(pitReader), query, size, searchAfter);
    }

    private ShardSearchResult execute(IndexSearcher searcher, Query query,
                                       int size, FieldDoc after) throws IOException {
        // searchAfter(ScoreDoc, Query, int, Sort) returns TopDocs in Lucene 9 — explicit cast
        // is safe because passing a Sort guarantees Lucene returns a TopFieldDocs instance.
        final TopFieldDocs topDocs;
        if (after != null) {
            topDocs = (TopFieldDocs) searcher.searchAfter(after, query, size, PAGINATION_SORT);
        } else {
            topDocs = searcher.search(query, size, PAGINATION_SORT, true);
        }

        List<SearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
        StoredFields storedFields = searcher.storedFields();

        for (ScoreDoc sd : topDocs.scoreDocs) {
            FieldDoc fd  = (FieldDoc) sd;
            Document doc = storedFields.document(sd.doc);
            String id    = doc.get("_id");
            String srcJson = doc.get("_source");

            @SuppressWarnings("unchecked")
            Map<String, Object> source = objectMapper.readValue(srcJson, Map.class);

            // sortValues: [Float score, String _id]
            // Sent back to the client; reconstructed as FieldDoc on the next page request.
            // fd.fields[0] = Float  (from RelevanceComparator for FIELD_SCORE)
            // fd.fields[1] = BytesRef (from TermValComparator for SortField "_id")
            //
            // IMPORTANT: fd.score is NaN in the searchAfter path because
            // searchAfter(after, query, size, sort) does not guarantee score population.
            // fd.fields[0] is always set by RelevanceComparator when FIELD_SCORE is the
            // primary sort, so use it as the authoritative score for the cursor.
            List<Object> sortValues = List.of(
                fd.fields != null && fd.fields.length > 0
                    ? ((Number) fd.fields[0]).floatValue() : fd.score,
                fd.fields != null && fd.fields.length > 1
                    ? bytesRefToString(fd.fields[1]) : id
            );
            hits.add(new SearchHit(id, sd.score, source, sortValues));
        }
        return new ShardSearchResult(hits, topDocs.totalHits.value);
    }

    private String bytesRefToString(Object v) {
        return (v instanceof BytesRef br) ? br.utf8ToString() : String.valueOf(v);
    }

    // =========================================================================
    // PIT lifecycle
    // =========================================================================

    /**
     * Opens a stable snapshot at the last committed point.
     * DirectoryReader.open(directory) opens the most recent commit — NOT a live NRT reader.
     * Subsequent commits (writes) do NOT affect this reader.
     */
    public String openPIT() throws IOException {
        DirectoryReader snapshot = DirectoryReader.open(directory);
        String pitId = UUID.randomUUID().toString();
        pitReaders.put(pitId, snapshot);
        log.debug("PIT {} opened -> shard {}/{} ({} docs)",
            pitId, indexName, shardId, snapshot.numDocs());
        return pitId;
    }

    /**
     * Closes and releases a PIT snapshot reader.
     * IMPORTANT: must be called to prevent resource leaks (open file handles).
     */
    public void closePIT(String pitId) throws IOException {
        DirectoryReader r = pitReaders.remove(pitId);
        if (r != null) {
            r.close();  // releases file handles
            log.debug("PIT {} closed -> shard {}/{}", pitId, indexName, shardId);
        }
    }

    // =========================================================================
    // Query DSL parser
    // =========================================================================

    /**
     * Builds a Lucene Query from the JSON query DSL map.
     *
     * match     -> QueryParser on the TextField (analyzed, full-text)
     *              QueryParser.escape() prevents Lucene syntax injection from user input.
     * term      -> TermQuery on the .keyword StringField (unanalyzed, exact match)
     * match_all -> MatchAllDocsQuery
     */
    @SuppressWarnings("unchecked")
    public Query buildQuery(Map<String, Object> queryMap) throws ParseException {
        if (queryMap.containsKey("match")) {
            var m = (Map<String, Object>) queryMap.get("match");
            var e = m.entrySet().iterator().next();
            return new QueryParser(e.getKey(), analyzer)
                .parse(QueryParser.escape(e.getValue().toString()));
        }
        if (queryMap.containsKey("term")) {
            var t = (Map<String, Object>) queryMap.get("term");
            var e = t.entrySet().iterator().next();
            return new TermQuery(new Term(e.getKey() + ".keyword", e.getValue().toString()));
        }
        if (queryMap.containsKey("match_all")) {
            return new MatchAllDocsQuery();
        }
        throw new IllegalArgumentException("Unsupported query: " + queryMap.keySet());
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void close() throws IOException {
        // Step 1: release all PIT readers
        for (var e : pitReaders.entrySet()) {
            try { e.getValue().close(); }
            catch (IOException ex) {
                log.warn("Error closing PIT reader {}: {}", e.getKey(), ex.getMessage());
            }
        }
        pitReaders.clear();
        // Step 2: close searcher pool (releases its internal reader reference)
        searcherManager.close();
        // Step 3: flush+close writer (releases write lock)
        writer.close();
        // Step 4: close directory (closes OS file handles)
        directory.close();
        analyzer.close();
        log.info("Shard {}/{} closed cleanly", indexName, shardId);
    }

    /**
     * Returns the maxDoc of the live searcher's reader.
     * Used by DataController to clamp the searchAfter cursor's doc field.
     */
    public int getLiveMaxDoc() throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().maxDoc();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Returns the maxDoc of a pinned PIT reader.
     * Returns 0 if the PIT is not found (caller should handle).
     */
    public int getPITMaxDoc(String pitId) {
        DirectoryReader r = pitReaders.get(pitId);
        return r != null ? r.maxDoc() : 0;
    }

    public String getIndexName() { return indexName; }
    public int getShardId()      { return shardId; }
}
