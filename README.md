# Distributed Search Engine

A minimal, production-style Elasticsearch-like distributed search system built with **Java 21**, **Spring Boot 3.2**, and **Apache Lucene 9.10**.

## Architecture

```
                         ┌──────────────────────────────────────────┐
                         │              Coordinator :8080            │
                         │                                           │
                         │  IndexManager   ShardRouter (FNV-1a)     │
                         │  QueryPlanner   ScatterGatherExecutor     │
                         │  PITManager     ResultMerger              │
                         │  ReplicationOrchestrator  NodeRegistry    │
                         └────────┬──────────────────┬──────────────┘
                    HTTP/REST     │                  │     HTTP/REST
              ┌───────────────────┘                  └────────────────────┐
              ▼                                                            ▼
┌─────────────────────────┐                              ┌─────────────────────────┐
│    DataNode-1 :8081     │                              │    DataNode-2 :8082     │
│                         │                              │                         │
│  LuceneShard (idx/0)    │                              │  LuceneShard (idx/1)    │
│  LuceneShard (idx/2)    │                              │  LuceneShard (idx/3)    │
│  ShardManager           │                              │  ShardManager           │
│  ReplicationService     │                              │  ReplicationService     │
└─────────────────────────┘                              └─────────────────────────┘
```

### Request flows

| Operation | Flow |
|-----------|------|
| **Index doc** | Coordinator → FNV-1a hash → primary shard → commit → push to replicas |
| **Search** | Coordinator → scatter (virtual threads) → all shards → gather → merge & sort → top-K |
| **PIT open** | Coordinator → each shard → `DirectoryReader.open(dir)` snapshot → return shard PIT IDs |
| **search_after** | Client sends `[score, _id]` cursor → each shard applies `IndexSearcher.searchAfter()` against pinned reader |

### Modules

| Module | Key classes |
|--------|-------------|
| `shared-model` | 12 record DTOs, sealed `QueryClause` hierarchy (`MatchQuery`, `TermQuery`, `MatchAllQuery`) |
| `coordinator` | `IndexManager`, `ShardRouter`, `QueryPlanner`, `ScatterGatherExecutor`, `PITManager`, `ResultMerger`, `ReplicationOrchestrator` |
| `datanode` | `LuceneShard`, `ShardManager`, `ReplicationService`, `NodeHealthTracker` |

## Features

- **Index creation** — configurable shard count and replica factor; shards assigned round-robin across registered nodes
- **Dynamic document mapping** — arbitrary JSON ingested without a pre-defined schema; strings indexed as both `TextField` (analyzed) and `StringField` (keyword)
- **FNV-1a consistent-hash routing** — deterministic `docId → shardId` mapping with better avalanche than `String.hashCode()`
- **Full-text search** (`match`) — Lucene `QueryParser` + `StandardAnalyzer`; input is escaped to prevent syntax injection
- **Exact-match search** (`term`) — `TermQuery` on unanalyzed `.keyword` fields
- **Scatter-gather** — Java 21 virtual threads, one per shard; I/O-bound blocking parks cheaply without consuming platform threads
- **PIT + `search_after`** — stable deep pagination; PIT pins a `DirectoryReader` snapshot per shard; sort values `[score, _id]` are globally comparable across shards
- **Synchronous push replication** — coordinator pushes each document to replicas after primary commits

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| jq | any (for curl examples) |

## Build

```bash
mvn clean package -DskipTests
```

Produces:
- `coordinator/target/coordinator-1.0.0-SNAPSHOT.jar`
- `datanode/target/datanode-1.0.0-SNAPSHOT.jar`

## Run a Local Cluster

Start each process in a separate terminal (or use `nohup` / a process manager):

```bash
# Terminal 1 — Coordinator (port 8080)
java -jar coordinator/target/coordinator-1.0.0-SNAPSHOT.jar

# Terminal 2 — DataNode 1 (port 8081)
java -DNODE_ID=node-1 \
     -DNODE_PORT=8081 \
     -DNODE_STORAGE_ROOT=./data/node-1 \
     -jar datanode/target/datanode-1.0.0-SNAPSHOT.jar

# Terminal 3 — DataNode 2 (port 8082)
java -DNODE_ID=node-2 \
     -DNODE_PORT=8082 \
     -DNODE_STORAGE_ROOT=./data/node-2 \
     -jar datanode/target/datanode-1.0.0-SNAPSHOT.jar
```

Each DataNode self-registers with the coordinator on startup. Verify:

```bash
curl -s http://localhost:8080/nodes | jq '[.[].nodeId]'
# ["node-1","node-2"]
```

## API Reference

### Index lifecycle

```bash
# Create index
curl -s -X POST http://localhost:8080/indices \
  -H "Content-Type: application/json" \
  -d '{"indexName":"articles","shardCount":4,"replicaFactor":1}' | jq .

# Inspect index (shard assignments)
curl -s http://localhost:8080/indices/articles | jq .

# List all indices
curl -s http://localhost:8080/indices | jq .
```

### Document indexing

```bash
# With explicit ID
curl -s -X POST http://localhost:8080/indices/articles/documents \
  -H "Content-Type: application/json" \
  -d '{
    "id": "art-001",
    "source": {
      "title": "Distributed Systems Fundamentals",
      "author": "Alice",
      "year": 2024
    }
  }' | jq .
# {"id":"art-001","shardId":2,"primaryNodeUrl":"http://localhost:8081"}

# Without ID — coordinator assigns a UUID
curl -s -X POST http://localhost:8080/indices/articles/documents \
  -H "Content-Type: application/json" \
  -d '{"source":{"title":"Lucene Internals","author":"Bob","year":2023}}' | jq .
```

### Search

```bash
# Full-text search (analyzed)
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"match":{"title":"distributed systems"}},"size":5}' | jq .

# Exact term search (unanalyzed)
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"author":"Alice"}},"size":10}' | jq .

# Match all
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"match_all":{}},"size":10}' | jq .
```

Each hit includes a `sortValues` array (`[score, _id]`) used for `search_after`.

### Deep pagination with PIT + `search_after`

```bash
# 1. Open a PIT snapshot (TTL formats: "30s", "5m", "1h")
PIT=$(curl -s -X POST http://localhost:8080/indices/articles/pit \
  -H "Content-Type: application/json" \
  -d '{"keepAlive":"5m"}' | jq -r '.pitId')

# 2. Page 1 — no search_after
PAGE1=$(curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\"}")
echo "$PAGE1" | jq '[.hits[].id]'

# 3. Extract cursor from last hit
SA=$(echo "$PAGE1" | jq '.hits[-1].sortValues')  # e.g. [0.85, "doc-abc"]

# 4. Page 2 — pass cursor as search_after
PAGE2=$(curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\",\"searchAfter\":$SA}")
echo "$PAGE2" | jq '[.hits[].id]'

# 5. Repeat for further pages using PAGE2's last hit cursor ...

# 6. Close PIT when done (releases pinned DirectoryReaders on all shards)
curl -s -X DELETE "http://localhost:8080/indices/pit/$PIT"
```

### Node management

```bash
# List registered nodes
curl -s http://localhost:8080/nodes | jq .

# DataNode health check
curl -s http://localhost:8081/internal/health | jq .
```

## Design Decisions

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Scatter executor | `Executors.newVirtualThreadPerTaskExecutor()` | Each shard call blocks on HTTP I/O; virtual threads park cheaply — 100 shards = 100 virtual threads, not 100 OS threads |
| Shard routing | FNV-1a hash mod `shardCount` | Stronger avalanche than `String.hashCode()`; nearby IDs like `user-1`, `user-2` disperse well |
| PIT snapshot | `DirectoryReader.open(directory)` | Opens at the last *committed* segment list — immutable; NRT reader (`open(writer)`) would expose uncommitted writes |
| `search_after` cursor | `[Float score, String _id]` | Globally comparable across shards; avoids Lucene internal doc IDs which are shard-local and segment-relative |
| `FieldDoc.doc` for cursor | `0` (not `Integer.MAX_VALUE`) | Lucene asserts `after.doc < maxDoc`; since `_id` is unique the doc-ID tiebreaker is never triggered, so any valid in-bounds value works |
| Replication | Coordinator-mediated synchronous push | Simple to reason about; swap for async + quorum acks (e.g. Raft) for higher write throughput |
| Routing table | In-memory `ConcurrentHashMap` | Designed to be pluggable — extract an `IndexMetaStore` interface and back it with etcd or ZooKeeper |
| Field indexing | `TextField` + `StringField(.keyword)` per string | `TextField` for scored full-text (`match`); `.keyword` for exact unanalyzed term queries (`term`) |

## Known Limitations

- **No shard rebalancing** — shard assignment is fixed at index creation; adding nodes does not migrate existing shards
- **In-memory state** — coordinator routing table is lost on restart; re-create indices after a coordinator bounce
- **No authentication** — internal and public APIs are open; protect with mTLS or a network policy in production
- **Per-document commit** — `IndexWriter.commit()` after every write is durable but limits write throughput; production should batch commits on a timer or buffer threshold
- **Replication is best-effort** — replica failures are logged but do not fail the write; stale replicas are not auto-recovered
