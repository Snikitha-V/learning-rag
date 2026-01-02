# RAG Learning Application

A Retrieval-Augmented Generation (RAG) application for learning management, combining semantic search, SQL queries, and LLM-powered responses.

## What’s Included (customer-ready)
- Spring Boot RAG API (8080) with hybrid dense + BM25 retrieval and factual SQL paths
- Conversation gateway (3000) for session-aware rewrites, deterministic Qdrant lookups, metrics, and safe API surface
- Qdrant + PostgreSQL backing stores, deterministic UUID upserts for vector payloads
- Production safety knobs: DB role separation (reader/editor/admin), optional tenant RLS, optional write audit logging
- Docs for enabling RLS & audit later: [docs/RLS_AND_AUDIT_GUIDE.md](docs/RLS_AND_AUDIT_GUIDE.md)

## Overview

This is a **microservice application** built with Spring Boot that implements a hybrid RAG system. It's independently deployable, owns a single business capability (retrieval-augmented generation), and exposes a unified API for querying.

Key components:
- **PostgreSQL**: Store learning chunks, courses, topics, classes, and assignments
- **Qdrant**: Vector database for semantic similarity search with embeddings
- **Mistral 7B LLM**: Generate intelligent responses using retrieved context
- **Hybrid Retrieval**: Combines SQL queries for structured data + vector search for semantic matching

## Architecture & Retrieval Pipelines

### Overall Flow
```
User Request
    ↓
Spring Boot API (Port 8080)
    ↓
Intent Classifier (FACTUAL/SEMANTIC/MIXED)
    ├─→ FACTUAL Pipeline
    ├─→ SEMANTIC Pipeline  
    └─→ MIXED Pipeline
    ↓
Prompt Builder + LLM Generation (Mistral 7B)
    ↓
JSON Response with Answer + Sources
```

### SEMANTIC Query Pipeline (RAG Only)
For queries like "What are the main topics in Machine Learning?" or "Describe each course"
```
SEMANTIC Query
    ↓
1. EMBEDDING (ONNX all-mpnet-base-v2, 768 dims)
    ↓
2. DENSE RETRIEVAL (Qdrant vector search, top-40)
    ↓
3. BM25 LEXICAL SEARCH (Lucene index, top-20)
    ├─ Merges both results by chunk_id
    └─ Deduplicates, fetches missing vectors
    ↓
4. MMR RERANKING (Max Marginal Relevance for diversity)
    ↓
5. CROSS-ENCODER RERANKING (ONNX ms-marco-miniLM, top-10)
    └─ Semantic scoring + sorting by relevance
    ↓
6. CONTEXT ASSEMBLY (top 5 chunks)
    ↓
7. LLM GENERATION (Mistral 7B, via llama-server:8081)
    ↓
Answer + Sources
```

## Security & Safety (built-in)
- **DB least-privilege**: reader/editor/admin roles created in Postgres; ship with reader-only credentials for RAG/LLM. Editor is for admin/upload only; admin for DB maintenance.
- **LLM sandboxing**: LLM and gateway use reader-only access; SQL is fixed/prepared (no dynamic SQL from prompts).
- **Deterministic IDs**: Qdrant points use Java UUID.v3(nameUUIDFromBytes) of `chunk_id`, enabling exact payload fetches and cache hits in the gateway.
- **Optional isolation & audit**: Tenant Row-Level Security (RLS) and write auditing are documented and off by default — see [docs/RLS_AND_AUDIT_GUIDE.md](docs/RLS_AND_AUDIT_GUIDE.md).
- **Gateway safety**: Session-aware query rewriting, metrics, health, and payload caching; no write surfaces exposed.

### FACTUAL Query Pipeline (SQL + RAG)
For queries like "List all courses" or "How many topics are there?"
```
FACTUAL Query
    ↓
1. SQL EXECUTION (PostgreSQL direct query)
    ├─ Extracts structured data (courses, topics, classes)
    └─ Returns authoritative results
    ↓
2. OPTIONAL RAG CONTEXT (enhance with semantic chunks)
    ├─ EMBEDDING (ONNX)
    ├─ DENSE RETRIEVAL (Qdrant)
    ├─ BM25 LEXICAL SEARCH (Lucene)
    ├─ MERGE & DEDUPE
    ├─ MMR RERANKING
    └─ CROSS-ENCODER RERANKING
    ↓
3. CONTEXT ASSEMBLY (SQL results + RAG context)
    ↓
4. LLM GENERATION (combines authoritative SQL with supporting RAG evidence)
    ↓
Answer + Sources (marked as SQL or RAG)
```

### MIXED Query Pipeline (Intent-based routing)
For queries like "When did I learn about supervised learning?"
```
MIXED Query
    ↓
1. DETECT INTENT (time-based, requires both SQL metadata + semantic search)
    ↓
2. SQL EXECUTION (get classes with learned_at timestamps)
    ↓
3. SEMANTIC RETRIEVAL (same as SEMANTIC pipeline)
    ├─ Dense + BM25 + Merge + MMR + Cross-Encoder
    └─ Find relevant topic/class information
    ↓
4. COMBINE RESULTS (metadata + semantic match)
    ↓
5. LLM GENERATION (with full context)
    ↓
Answer + Sources
```

### Hybrid Retrieval Details (Dense + BM25)
The system uses **two complementary search methods**:

**Dense Retrieval (Qdrant)**
- Semantic similarity search
- Uses query embedding vector
- Returns top 40 candidates based on cosine similarity
- Effective for: understanding context, meaning, relationships

**BM25 Lexical Search (Lucene)**
- Keyword/term frequency matching
- Searches inverted index built from chunk text
- Returns top 20 candidates based on term relevance
- Effective for: exact phrase matching, technical terms, direct matches

**Merging Strategy**
- Combines results by chunk_id (deduplicates)
- Preserves both dense and lexical scores
- MMR algorithm selects diverse + relevant results
- Cross-encoder provides final semantic scoring

## Prerequisites

- **Docker** and **Docker Compose** (for containerized setup)
- **Java 17+** (for local development)
- **Maven 3.8+** (for building)
- **Git** (for cloning repository)

## Quick Start

### Option 1: Run Everything with Docker Compose (Recommended)

```bash
cd rag-learning
docker-compose up -d
```

This starts:
- **PostgreSQL 15** (Port 5432)
- **Qdrant 1.12.0** (Port 6333)
- **Mistral 7B LLM** (Port 8081)
- **Spring Boot App** (Port 8080)

**Environment Variables:**
```bash
export DB_PASS="your_postgres_password"  # Set your PostgreSQL password
```

Access the application: `http://localhost:8080`

### Option 2: Run Locally with Pre-existing Docker Services

If you already have PostgreSQL and Qdrant running in Docker:

```bash
cd rag-learning

# Set database password (use your own)
export DB_PASS="your_postgres_password"

# Build and run Spring Boot app
mvn clean compile
mvn org.springframework.boot:spring-boot-maven-plugin:3.2.4:run
```

The app will start on `http://localhost:8080`

### Option 2b: Conversation Gateway (front-door, optional but recommended)

Runs on port 3000 and fronts the backend with session-aware rewrites, metrics, and deterministic Qdrant fetches.

```bash
cd rag-learning/conversation-gateway
npm install
BACKEND_BASE=http://localhost:8080 \
QDRANT_BASE=http://localhost:6333 \
PORT=3000 \
npm start
```

Health: `http://localhost:3000/health` • Metrics: `http://localhost:3000/metrics`

### Option 3: Run JAR After Building

```bash
cd rag-learning

# Build JAR
mvn clean package -DskipTests

# Start Docker services
docker-compose up -d

# Run the JAR with environment variables
export DB_PASS="your_postgres_password"
java -jar target/rag-learning-1.0-SNAPSHOT.jar
```

## Database Setup

### Initial Data Population

The application includes SQL scripts to populate the database:

```bash
# 1. Populate courses, topics, and chunks
docker exec rag-learning-postgres-1 psql -U postgres -d learning_db -f temp_chunks.sql

# 2. Populate classes (lectures) for all courses
docker exec rag-learning-postgres-1 psql -U postgres -d learning_db -f populate_classes.sql
docker exec rag-learning-postgres-1 psql -U postgres -d learning_db -f populate_classes_c3_c5.sql

# 3. Populate assignments
docker exec rag-learning-postgres-1 psql -U postgres -d learning_db -f populate_assignments.sql
```

### Database Schema

- **chunks**: Learning content (courses, topics, summaries, classes)
- **courses**: Course definitions (5 courses: C1-C5)
- **topics**: Topics within courses (5 per course = 25 total)
- **classes**: Learning classes/lectures (5 per topic = 125 total)
- **assignments**: Topic-based assignments (25 total)

## Embeddings Generation

The application uses **all-mpnet-base-v2** ONNX model for embeddings.

### Regenerate Embeddings

```bash
# Navigate to rag-learning directory
cd rag-learning

# Ensure all dependencies are compiled
mvn clean compile

# Run the embedding uploader
java -cp "target/classes;target/dependency/*" org.example.EmbeddingUploader chunks.jsonl
```

This uploads 305 vectors to Qdrant's `learning_chunks` collection.

## API Usage

### Query Endpoint

**POST** `/api/v1/query`

```json
{
  "query": "What are the main topics in Machine Learning?"
}
```

**Response:**
```json
{
  "answer": "The main topics in Machine Learning course include...",
  "sources": [
    {
      "chunk_id": "C1-T1",
      "chunk_type": "TOPIC",
      "title": "Supervised Learning",
      "source": "RAG"
    }
  ],
  "intent": "SEMANTIC",
  "confidence": 0.85
}
```

## Key Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Container orchestration for all services |
| `pom.xml` | Maven dependencies and build configuration |
| `src/main/java/org/example/App.java` | Spring Boot entry point |
| `src/main/java/org/example/RetrievalService.java` | Hybrid RAG orchestration |
| `src/main/java/org/example/QdrantClient.java` | Vector search client |
| `src/main/resources/application.properties` | Application configuration |
| `conversation-gateway/proxy.js` | Node gateway front-door (session, rewrites, metrics) |
| `conversation-gateway/docker-compose.gateway.yml` | Optional gateway/docker wiring |
| `docs/RLS_AND_AUDIT_GUIDE.md` | How to enable tenant RLS and audit logging (optional) |
| `chunks.jsonl` | Learning chunks in JSONL format for embedding |
| `temp_chunks.sql` | SQL script with 305 chunks |
| `populate_classes.sql` | SQL script for classes data |
| `populate_assignments.sql` | SQL script for assignments data |

## Configuration

### Environment Variables

```bash
DB_HOST=localhost              # PostgreSQL host
DB_PORT=5432                   # PostgreSQL port
DB_NAME=learning_db            # Database name
DB_USER=rag_reader_user        # PostgreSQL user (use reader-only for RAG/LLM)
DB_PASS=pwd_reader             # PostgreSQL password (override per env)

QDRANT_HOST=localhost          # Qdrant host
QDRANT_PORT=6333              # Qdrant port

# If you run an admin/upload profile, use editor creds for that process only:
# DB_USER=rag_editor_user
# DB_PASS=pwd_editor

LLM_HOST=localhost             # Mistral LLM host
LLM_PORT=8081                 # Mistral LLM port
```

### Application Properties

Edit `src/main/resources/application.properties` for:
- Server port (default: 8080)
- Logging levels
- JDBC connection pooling

## Troubleshooting

### Port Already in Use
```bash
# Windows - Kill process on port 8080
Get-NetTCPConnection -LocalPort 8080 | Stop-Process -Force

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

### Qdrant Not Responding
```bash
# Check Qdrant health
curl http://localhost:6333/health

# Restart Qdrant
docker restart rag-learning-qdrant-1
```

### PostgreSQL Connection Failed
```bash
# Check PostgreSQL status
docker logs rag-learning-postgres-1

# Verify password
echo $DB_PASS
```

### LLM Server Not Available
```bash
# Check if llama-server is running
curl http://localhost:8081/health

# Restart the service in docker-compose.yml
```

## Development Notes

- **Framework**: Spring Boot 3.2.4 with Spring Data JPA
- **Language**: Java 17
- **Build Tool**: Maven 3.13.0
- **ORM**: Hibernate
- **Vector DB Client**: OKHttp3
- **Embeddings**: ONNX Runtime (HuggingFace Tokenizers)
- **Cross-Encoder**: ONNX-based reranking model

## Performance

- **Embedding Generation**: ~0.04s per chunk (305 chunks = ~13 seconds total)
- **Vector Search**: ~34ms per query (Qdrant dense retrieval)
- **LLM Generation**: ~2-5 seconds per response (Mistral 7B)
- **End-to-end Query**: ~6-10 seconds (embedding + search + generation)

## Notes

This repository does not include automated CI/CD workflows. If you later want CI/CD, add workflows under `.github/workflows/` to suit your needs.
