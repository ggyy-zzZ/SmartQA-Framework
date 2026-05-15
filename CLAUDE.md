# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SmartQA-Framework is a knowledge base Q&A assistant system built with Spring Boot 3.5.13 (Java 21). It learns from user-provided documents and structured data, uses multiple retrieval approaches (vector search via Qdrant, graph via Neo4j, MySQL), and generates answers using MiniMax LLM.

## Build & Run Commands

```bash
./mvnw.cmd clean                    # Clean build artifacts
./mvnw.cmd compile                 # Compile the project
./mvnw.cmd test                     # Run unit tests
./mvnw.cmd test -Dtest=ClassName    # Run single test class
./mvnw.cmd package                 # Build JAR
./mvnw.cmd spring-boot:run         # Run application (port 8080)
./mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"  # Run on custom port
```

## Architecture Overview

### Multi-source Retrieval Pipeline

The core orchestration flows through `QaAskOrchestrator` which coordinates:
1. **Intent Routing** (`IntentRouterService`) - Determines query type (vector, graph, mysql, document, or hybrid)
2. **Parallel Retrieval** - GraphRetriever, VectorRetriever (Qdrant), MySQLRetriever, DocumentRetriever
3. **Evidence Merge** - Combines results from multiple sources
4. **LLM Generation** - `MiniMaxClient` generates answer with confidence score

### Key Modules

| Module | Purpose |
|--------|---------|
| `qa/core` | Core models: `ContextChunk`, `IntentDecision`, `QaScopes` |
| `qa/intent` | Intent routing decisions |
| `qa/retrieval` | Multi-source retrieval (Graph, Vector, MySQL, Document, SQL) |
| `qa/learning` | Active learning, CSV ingest, schema catalog, scheduled jobs |
| `qa/answer` | LLM client and answer generation |
| `qa/orchestration` | Main `QaAskOrchestrator` and SSE streaming |
| `qa/response` | Conversation management and logging |
| `qa/web` | REST controller (`QaController`) and DTOs |
| `qa/sedimentation` | Feedback persistence and queue management |
| `graph` | Neo4j configuration |

### Response Protocol

All Q&A endpoints return structured responses with `answer`, `evidence`, `confidence`, `canAnswer`, and `route` fields.

### Active Learning Workflow

Feedback flows through: `User Feedback` → `SedimentationQueue` → `Candidate Generation` → `Row Audit` → `Publish`

### Structured Data Gate

Ingest gate validates structured data via manifest-based approach without DML to business tables. See `openspec/design/structured-ingest-gate.md`.

## Key REST Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/qa/ask` | POST | Synchronous Q&A |
| `/qa/ask/stream` | GET | Streaming Q&A (SSE) |
| `/qa/structured/csv-ingest` | POST | CSV ingestion |
| `/qa/structured/ingest-gate` | POST | Structured ingest validation |
| `/qa/sedimentation/pending` | GET | Pending feedback queue |
| `/qa/feedback` | POST | Submit feedback |

## Configuration

Main config: `src/main/resources/application.properties`

Required environment variable: `MINIMAX_API_KEY`

External services (must be running):
- **Qdrant**: `localhost:6333` (vector store)
- **Neo4j**: `bolt://localhost:7687` (graph)
- **MySQL**: `localhost:3306/assistant` (relational)

## Documentation References

- `openspec/AGENTS.md` - Development conventions
- `openspec/project.md` - Project architecture details
- `openspec/specs/knowledge-assistant/spec.md` - Technical specification
- `.cursor/skills/enterprise-qa-grounded-assistant/SKILL.md` - Claude Code skill for this domain

## Database Setup

MySQL bootstrap: `data/sql/mysql/assistant_bootstrap.sql`
Neo4j bootstrap: `data/neo4j/assistant_bootstrap.cypher`
Flyway migrations: `src/main/resources/db/migration/assistant/V1__assistant_qa_extensions.sql`