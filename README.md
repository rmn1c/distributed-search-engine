# Distributed Search Engine

A minimal, production-style Elasticsearch-like distributed search system built with **Java 21**, **Spring Boot 3.2**, and **Apache Lucene 9.10**.

## Architecture

```
┌────────────────────┐        HTTP/REST        ┌──────────────────────┐
│   Coordinator      │ ──────────────────────► │  DataNode (port 8081)│
│   (port 8080)      │                          │  Lucene shards 0,2   │
│                    │ ──────────────────────► │  DataNode (port 8082)│
│  - IndexManager    │                          │  Lucene shards 1,3   │
│  - ShardRouter     │                          └──────────────────────┘
│  - QueryPlanner    │
│  - ScatterGather   │
│  - PITManager      │
│  - ResultMerger    │
└────────────────────┘
```

### Modules

| Module | Description |
|--------|-------------|
| `shared-model` | DTOs, sealed query DSL (`QueryClause`) |
| `coordinator` | Index lifecycle, routing table, scatter-gather, PIT management |
| `datanode` | Lucene shard hosting, replication, PIT snapshots |

## Features

- **Index creation** with configurable shards and replicas
- **Dynamic document mapping** — arbitrary JSON, no pre-defined schema
- **FNV-1a consistent hash routing** for deterministic shard assignment
- **Full-text search** (`match`) via Lucene `QueryParser` + `StandardAnalyzer`
- **Exact match** (`term`) via Lucene `TermQuery` on `.keyword` fields
- **Scatter-gather** with Java 21 virtual threads (one thread per shard, I/O-bound)
- **PIT + `search_after`** for stable deep pagination without score drift
- **Synchronous push replication** from primary to replicas via coordinator

## Build

```bash
# Requires Java 21+, Maven 3.9+
mvn clean package -DskipTests
```

## Run a Local Cluster

```bash
# Terminal 1 — Coordinator
java -jar coordinator/target/coordinator-1.0.0-SNAPSHOT.jar

# Terminal 2 — DataNode 1
java -DNODE_ID=node-1 -DNODE_PORT=8081 \
     -jar datanode/target/datanode-1.0.0-SNAPSHOT.jar

# Terminal 3 — DataNode 2
java -DNODE_ID=node-2 -DNODE_PORT=8082 \
     -jar datanode/target/datanode-1.0.0-SNAPSHOT.jar
```

## Quick API Tour

```bash
# Create index (4 shards, 1 replica)
curl -X POST http://localhost:8080/indices \
  -H "Content-Type: application/json" \
  -d '{"indexName":"articles","shardCount":4,"replicaFactor":1}'

# Index a document
curl -X POST http://localhost:8080/indices/articles/documents \
  -H "Content-Type: application/json" \
  -d '{"id":"art-1","source":{"title":"Distributed Systems","author":"Alice"}}'

# Full-text search
curl -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"match":{"title":"distributed"}},"size":5}'

# Exact term search
curl -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"author":"Alice"}},"size":5}'

# Create PIT + deep pagination
PIT=$(curl -s -X POST http://localhost:8080/indices/articles/pit \
  -H "Content-Type: application/json" \
  -d '{"keepAlive":"5m"}' | jq -r '.pitId')

# Page 1
PAGE1=$(curl -s -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\"}")

# Page 2 (using sortValues from last hit)
SA=$(echo $PAGE1 | jq '.hits[-1].sortValues')
curl -X POST http://localhost:8080/indices/articles/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":{\"match_all\":{}},\"size\":3,\"pitId\":\"$PIT\",\"searchAfter\":$SA}"

# Close PIT
curl -X DELETE "http://localhost:8080/indices/pit/$PIT"
```

## Key Design Decisions

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Scatter executor | Virtual threads (`Executors.newVirtualThreadPerTaskExecutor`) | Each shard call is I/O-bound; virtual threads park cheaply |
| Shard routing | FNV-1a hash mod shardCount | Better avalanche than `String.hashCode()` |
| PIT snapshot | `DirectoryReader.open(directory)` | Stable committed-segment snapshot; NRT reader would drift |
| Replication | Coordinator-mediated synchronous push | Simple; swap for async + quorum acks for higher throughput |
| Sort for pagination | `FIELD_SCORE` + `_id` (SortedDocValues) | Globally comparable across shards; no internal doc IDs |
| Routing table | In-memory `ConcurrentHashMap` | Pluggable — wrap in an interface to swap for etcd/ZooKeeper |
