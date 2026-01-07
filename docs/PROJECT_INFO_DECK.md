# 📘 Retrieval-Augmented Learning System (RAG) — Project Deck

This deck describes what the repository implements today. Wording is precise and aligned to the codebase: Postgres `chunks` + structured tables, ONNX embeddings, Qdrant, Lucene, hybrid retrieval, and a pluggable LLM interface.

---

## Slide 1 — Persona & Problem (Project-Grounded)

**Target Persona**
- Educational institutions managing multiple courses, topics, classes, assignments, and shared academic content.

**Problem Observed**
- Curriculum content exists as static documents (PDFs, LMS pages, portals).
- Students and faculty repeatedly ask clarifying questions across touchpoints.
- No single, queryable source of truth for curriculum information.

**What This Project Achieves (What It Actually Does)**
- Converts curriculum data stored in **PostgreSQL** into a queryable RAG knowledge base.
- Exposes a Spring Boot API for natural-language Q&A.
- Generates answers using an LLM that is prompted with retrieved curriculum context (from Postgres/Qdrant/Lucene).

---

## Slide 2 — Detailed Pipeline & Architecture (Actual Project Flow)

### 1. Curriculum Storage
- Primary data: **PostgreSQL** tables used in the repo:
  - `courses`, `topics`, `classes`, `assignments`, `assignment_topics`
  - `chunks(chunk_id, title, text, chunk_type, metadata)` — the RAG content table; `metadata` is JSON.
- Data population scripts are present (`temp_chunks.sql`, `populate_classes.sql`, `populate_assignments.sql`).

### 2. Chunk Dataset
- Chunks represent small semantic content units with stable `chunk_id` and metadata (topic/class ids, timestamps, etc.).

### 3. Embedding Generation
- Local ONNX embedding model is used: **all-mpnet-base-v2** (embedding dim = 768).
- Embeddings produced via `OnnxEmbedder` (no external embedding API required by default).

### 4. Vector Storage
- Embeddings are upserted to **Qdrant** by `EmbeddingUploader`.
- Qdrant point IDs are deterministic UUIDs derived from `chunk_id` so upserts are idempotent.
- Payload includes `chunk_id`, `chunk_type`, `title`, and `metadata` for filtering.

### 5. Query Flow (Hybrid Retrieval)
- Query → embed (ONNX) → Qdrant dense search (top-K)
- Parallel BM25 lexical search via Lucene index
- Merge/dedupe by `chunk_id`, MMR selection, then cross-encoder rerank (local ONNX cross-encoder)
- Fetch full chunk rows from Postgres for final context assembly
- Prompt LLM with assembled context to generate answer

### 6. Context Assembly + Answer
- Final chunks fetched from `chunks` table and assembled into the LLM prompt via `PromptBuilder`.
- LLM call goes through a Java `LLMProvider` interface (factory selects provider).

**Key Guarantee (Accurate Statement)**
> The application prompts the LLM with curriculum content retrieved from the database and Qdrant; the system does not pull external web data during retrieval.

---

## Slide 3 — Institution Database Usage & Security (Project-Level Details)

### Database-First Design (what’s present)
- PostgreSQL is the authoritative source for curriculum content (`chunks` + structured tables).
- `chunks.metadata` stores contextual fields (topic/class ids, learned_at, resource references).
- `DataFetcher` and `SqlService` contain queries to list courses, topics, classes, assignments, and to fetch chunk rows by `chunk_id`.

### Access Control (what’s present / documented)
- The repo documents DB-side tenant isolation and audit steps in `docs/RLS_AND_AUDIT_GUIDE.md` (how to enable RLS, policies, `SET app.tenant_id`).
- README and docs recommend using a read-only DB user for the RAG query path to reduce risk.
- App-level auth is not implemented by default; DB-side recommendations are provided for hardening.

### Operational Notes
- Qdrant payloads store `chunk_id` and `metadata`, enabling filtering by course/topic/tenant at vector-search time.
- Documentation covers role separation and audit logging options (pgAudit, Postgres settings).

---

## Slide 4 — Student Isolation & Privacy (Repo Capabilities & How-to)

### Similar Mechanisms Present
- `chunks.metadata` includes enrollment-relevant fields (e.g., `class_id`, `topic_id`, `learned_at`) as shown in `temp_chunks.sql`.
- `EmbeddingUploader` writes metadata into Qdrant payloads so vector searches can be filtered by course/topic/tenant.
- `docs/RLS_AND_AUDIT_GUIDE.md` explains how to enable Postgres Row Level Security (RLS) and set the per-request `app.tenant_id`.

### What the Repo Enables Today
- Filtering retrieval by course/topic is supported by:
  - SQL queries (via `SqlService`) for authoritative results
  - Qdrant payload filters for semantic search scoping
- The optional `conversation-gateway` provides session ID wiring and can carry session-scoped behavior when deployed.

### How to Enforce Per-Student Isolation (next steps)
- Set `app.tenant_id` on each DB connection (request interceptor) and enable RLS policies on `chunks`/other tables.
- Apply Qdrant filters based on the same tenant/course list during vector search.
- Use a read-only DB role for the public query path and restrict write roles to ingestion workflows.

---

## Slide 5 — LLM Pluggability (Engineering Design Present)

### LLM Abstraction (what’s implemented)
- LLM access is abstracted via a Java `LLMProvider` interface and a `LLMFactory`.
- Implementations include local llama-server-style provider, OpenAI provider, and a generic HTTP provider.

### What This Enables (accurate)
- Swap LLM providers without changing retrieval, chunking, or embedding workflows.
- No need to re-chunk or re-embed when changing the model backend.

---

## Slide 6 — Readiness & Reality Check (Accurate, Not Overstated)

### Strengths in This Repo
- Full hybrid retrieval implementation: Qdrant (dense) + Lucene (BM25) + MMR + cross-encoder reranking.
- Local ONNX-based embedding and reranking models.
- Deterministic ingestion into Qdrant (UUID from `chunk_id`) for idempotent upserts.
- Health/metrics via Spring Boot Actuator (configurable in `application.properties`).
- LLM provider abstraction for flexible model backend integration.

### Items for Production Hardening (practical next steps)
- App-level AuthN/AuthZ (API authentication, role checks)
- Per-request tenant binding (RLS) and DB connection hygiene for pooled connections
- Persistent session store (if strong conversation memory is required)
- Monitoring, SLOs, and operational runbooks for model/DB/DBA maintenance

### Final Outcome (precise)
A working hybrid RAG system that answers curriculum queries by retrieving relevant chunks from Postgres/Qdrant/Lucene and generating responses through a configurable LLM interface. The repo includes ingestion scripts, embedding uploader, hybrid retrieval, and documentation for DB-side isolation/audit.

---

If you want, I can now:
- Convert this markdown into PPT bullets (PowerPoint/Google Slides friendly)
- Produce a 5-minute demo slide variant (shortened)
- Create a 1-slide architecture diagram description

Tell me which format you want next.