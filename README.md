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
| Docker + Compose | 24+ (for containerised run) |
| jq | any (for curl examples) |

---

## Quick start — Docker (recommended)

### 1. Clone and start the cluster

```bash
git clone https://github.com/rmn1c/distributed-search-engine.git
cd distributed-search-engine

docker compose up --build
```

Docker Compose builds two images (coordinator, datanode) using multi-stage builds, then starts three containers:

| Container | Port | Role |
|-----------|------|------|
| `search-coordinator` | 8080 | Routes requests, manages index metadata |
| `search-node-1` | 8081 | Holds shards 0 and 2 |
| `search-node-2` | 8082 | Holds shards 1 and 3 |

The coordinator performs a health-check on itself before the data nodes start, so node self-registration is always race-free.

### 2. Verify the cluster is up

```bash
curl -s http://localhost:8080/nodes | jq '[.[].nodeId]'
# ["node-1","node-2"]
```

### 3. Stop and clean up

```bash
# Stop containers, keep volumes (shard data persists)
docker compose stop

# Stop and remove everything including volumes
docker compose down -v
```

---

## Quick start — bare JARs

### Build

```bash
mvn clean package -DskipTests
```

Produces:
- `coordinator/target/coordinator-1.0.0-SNAPSHOT.jar`
- `datanode/target/datanode-1.0.0-SNAPSHOT.jar`

### Run

Start each process in a separate terminal (or use `nohup`):

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

Each DataNode self-registers with the coordinator on startup.

---

## Usage examples

### Create an index

```bash
curl -s -X POST http://localhost:8080/indices \
  -H "Content-Type: application/json" \
  -d '{"indexName":"articles","shardCount":4,"replicaFactor":1}' | jq .
```

```json
{
  "index": "articles",
  "shardCount": 4,
  "replicaFactor": 1,
  "acknowledged": true
}
```

### Index documents

```bash
# With an explicit ID
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
```

```json
{
  "id": "art-001",
  "shardId": 2,
  "primaryNodeUrl": "http://localhost:8081"
}
```

```bash
# Without an ID — coordinator assigns a UUID
curl -s -X POST http://localhost:8080/indices/articles/documents \
  -H "Content-Type: application/json" \
  -d '{"source":{"title":"Lucene Internals","author":"Bob","year":2023}}' | jq .
```

```json
{
  "id": "f3a1bc7d-...",
  "shardId": 1,
  "primaryNodeUrl": "http://localhost:8082"
}
```

### Full-text search (`match`)

Analyzed with `StandardAnalyzer` — case-folded, stop words removed.

```bash
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"match":{"title":"distributed systems"}},"size":5}' | jq .
```

```json
{
  "hits": [
    {
      "id": "art-001",
      "score": 0.6931,
      "source": {
        "title": "Distributed Systems Fundamentals",
        "author": "Alice",
        "year": 2024
      },
      "sortValues": [0.6931, "art-001"]
    }
  ],
  "total": 1,
  "pitId": null
}
```

### Exact-match search (`term`)

Unanalyzed — matches the field value verbatim.

```bash
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"author":"Alice"}},"size":10}' | jq .
```

### Match all

```bash
curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"match_all":{}},"size":10}' | jq .
```

Every hit includes a `sortValues` array (`[score, _id]`) used as the `search_after` cursor.

---

### Deep pagination with PIT + `search_after`

PIT (Point in Time) pins a `DirectoryReader` snapshot on every shard, guaranteeing a stable view even as new documents are written. Combine it with `search_after` for efficient, duplicate-free deep pagination.

```bash
# 1. Open a PIT (TTL formats: "30s", "5m", "1h")
PIT=$(curl -s -X POST http://localhost:8080/indices/articles/pit \
  -H "Content-Type: application/json" \
  -d '{"keepAlive":"5m"}' | jq -r '.pitId')

# 2. Page 1 — no search_after
PAGE1=$(curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\"}")
echo "$PAGE1" | jq '[.hits[].source.title]'
# ["Distributed Systems Fundamentals", "Lucene Internals", "Search Engine Design"]

# 3. Extract cursor from the last hit of the page
SA=$(echo "$PAGE1" | jq '.hits[-1].sortValues')
# [0.85, "art-007"]

# 4. Page 2 — pass cursor as search_after
PAGE2=$(curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\",\"searchAfter\":$SA}")
echo "$PAGE2" | jq '[.hits[].source.title]'

# 5. Repeat: extract cursor from PAGE2's last hit, fetch PAGE3, etc.
#    Stop when .hits is empty — that signals exhaustion.

# 6. Close PIT when done (releases pinned DirectoryReaders on all shards)
curl -s -X DELETE "http://localhost:8080/indices/pit/$PIT"
```

**Pagination script** — iterate all pages automatically:

```bash
PIT=$(curl -s -X POST http://localhost:8080/indices/articles/pit \
  -H "Content-Type: application/json" \
  -d '{"keepAlive":"5m"}' | jq -r '.pitId')

SA="null"
PAGE=0

while true; do
  PAGE=$((PAGE + 1))

  if [ "$SA" = "null" ]; then
    BODY="{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\"}"
  else
    BODY="{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\",\"searchAfter\":$SA}"
  fi

  RESP=$(curl -s -X POST http://localhost:8080/indices/articles/search \
    -H "Content-Type: application/json" -d "$BODY")

  HITS=$(echo "$RESP" | jq '.hits | length')
  [ "$HITS" -eq 0 ] && echo "Done — $((PAGE-1)) pages" && break

  echo "Page $PAGE:"
  echo "$RESP" | jq -r '.hits[].source.title'

  SA=$(echo "$RESP" | jq '.hits[-1].sortValues')
done

curl -s -X DELETE "http://localhost:8080/indices/pit/$PIT"
```

---

### Node management

```bash
# List registered nodes
curl -s http://localhost:8080/nodes | jq .

# Inspect an index (shard assignments)
curl -s http://localhost:8080/indices/articles | jq .

# List all indices
curl -s http://localhost:8080/indices | jq .

# DataNode health check
curl -s http://localhost:8081/internal/health | jq .
# {"status":"UP","uptimeMs":12345,"shardCount":2}

# Remove a node from the registry
curl -s -X DELETE http://localhost:8080/nodes/node-1
```

---

## Docker details

### Build images individually

```bash
# Coordinator image
docker build -f coordinator/Dockerfile -t search-coordinator:latest .

# DataNode image
docker build -f datanode/Dockerfile -t search-datanode:latest .
```

Both Dockerfiles use a two-stage build:

1. **Build stage** (`maven:3.9-eclipse-temurin-21`) — compiles only the relevant module and its dependencies; the full Maven cache is discarded after this stage.
2. **Runtime stage** (`eclipse-temurin:21-jre`) — ships only the JRE and the fat JAR; no source code or build tools included.

### Run a single DataNode container

```bash
docker run -d \
  --name my-node-3 \
  -e NODE_ID=node-3 \
  -e NODE_PORT=8083 \
  -e NODE_STORAGE_ROOT=/data/node-3 \
  -e COORDINATOR_URL=http://host.docker.internal:8080 \
  -p 8083:8083 \
  -v node3-data:/data/node-3 \
  search-datanode:latest
```

Replace `host.docker.internal` with the coordinator container's IP or service name when both run on the same Docker network.

### Environment variables (DataNode)

| Variable | Default | Description |
|----------|---------|-------------|
| `NODE_ID` | `node-1` | Unique node identifier registered with the coordinator |
| `NODE_PORT` | `8081` | HTTP port the node listens on |
| `NODE_STORAGE_ROOT` | `./data/node-1` | Root directory for Lucene shard data |
| `COORDINATOR_URL` | `http://localhost:8080` | Base URL of the coordinator |

---

## Design decisions

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Scatter executor | `Executors.newVirtualThreadPerTaskExecutor()` | Each shard call blocks on HTTP I/O; virtual threads park cheaply — 100 shards = 100 virtual threads, not 100 OS threads |
| Shard routing | FNV-1a hash mod `shardCount` | Stronger avalanche than `String.hashCode()`; nearby IDs like `user-1`, `user-2` disperse well |
| PIT snapshot | `DirectoryReader.open(directory)` | Opens at the last *committed* segment list — immutable; NRT reader (`open(writer)`) would expose uncommitted writes |
| `search_after` cursor | `[Float score, String _id]` | Globally comparable across shards; avoids Lucene internal doc IDs which are shard-local and segment-relative |
| `FieldDoc.doc` for cursor | `maxDoc - 1` | Lucene asserts `after.doc < maxDoc`; using the highest valid doc ID ensures the cursor document is never treated as "after" its own cursor (avoids boundary duplicates) |
| Replication | Coordinator-mediated synchronous push | Simple to reason about; swap for async + quorum acks (e.g. Raft) for higher write throughput |
| Routing table | In-memory `ConcurrentHashMap` | Designed to be pluggable — extract an `IndexMetaStore` interface and back it with etcd or ZooKeeper |
| Field indexing | `TextField` + `StringField(.keyword)` per string | `TextField` for scored full-text (`match`); `.keyword` for exact unanalyzed term queries (`term`) |

## Known limitations

- **No shard rebalancing** — shard assignment is fixed at index creation; adding nodes does not migrate existing shards
- **In-memory coordinator state** — routing table is lost on coordinator restart; indices must be recreated after a bounce
- **No authentication** — internal and public APIs are open; protect with mTLS or a network policy in production
- **Per-document commit** — `IndexWriter.commit()` after every write is durable but limits write throughput; production should batch commits on a timer or buffer threshold
- **Replication is best-effort** — replica failures are logged but do not fail the write; stale replicas are not auto-recovered
