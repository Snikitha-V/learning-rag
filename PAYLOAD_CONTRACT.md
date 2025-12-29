# PAYLOAD_CONTRACT

Minimal payload contract for all chunks stored in the vector DB (required):

Every point payload must contain the following keys:

- `chunk_id` (string): stable unique id for the chunk (e.g., `TOPIC-11`)
- `title` (string): human-readable title or name for the chunk
- `chunk_type` (string): label describing the chunk type (COURSE, TOPIC, CLASS, DOC, PRODUCT, etc.)
- `metadata` (object): opaque JSON object with any schema-specific fields you need

Example payload:

```json
{
  "chunk_id": "TOPIC-11",
  "title": "Databases and SQL",
  "chunk_type": "COURSE",
  "metadata": { "topic_id": 11, "course_id": 3 }
}
```

Notes:
- The gateway relies only on `chunk_id`, `title`, and `chunk_type` to build ConversationState.
- `metadata` may contain any schema-specific fields; the gateway treats it as opaque.
- Use deterministic `chunk_id` generation on ingestion (EmbeddingUploader does this already).

Deterministic point id (recommended)
-----------------------------------

To make payload lookups reliable and fast, the ingestion pipeline (the `EmbeddingUploader`) creates a deterministic Qdrant point id from the original `chunk_id` so upserts are idempotent and the gateway can fetch payloads by point id.

- The embedding uploader converts the original `chunk_id` string into a UUID using Java's `UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8))` (MD5-based name UUID, RFC-4122 variant). This UUID is used as the Qdrant `id` when upserting points.
- The gateway computes the same UUID from `chunk_id` and performs a point-by-id GET to fetch `payload` (fast path). If the point is missing the gateway falls back to a scroll search by `chunk_id` (slow but compatible).

Qdrant upsert guidance
---------------------

- Upsert points with explicit `id` set to the deterministic UUID (see above), include `vector`, and include `payload` with keys: `chunk_id`, `title`, `chunk_type`, `metadata`.
- Use `vectors.size` matching your embedding dimension (e.g., 768) and `distance` set to `Cosine` or `Dot` depending on your normalization.
- Example upsert body (Java uploader already produces this format):

```json
{
  "points": [
    {
      "id": "<deterministic-uuid>",
      "vector": [ ... ],
      "payload": {
        "chunk_id": "TOPIC-11",
        "title": "Databases and SQL",
        "chunk_type": "COURSE",
        "metadata": { "topic_id": 11 }
      }
    }
  ]
}
```

Cache and gateway expectations
------------------------------

- The gateway will read only `chunk_id`, `title`, and `chunk_type` from the payload to build `ConversationState`. `metadata` is opaque and may be used by downstream clients.
- The gateway ships with a configurable in-process LRU cache (env `PAYLOAD_CACHE_MAX`, `PAYLOAD_CACHE_TTL_SEC`) and can be configured to use Redis for shared session state and shared caching.

Backward compatibility
----------------------

If you cannot set deterministic point ids for existing collections, the gateway will still work by falling back to the scroll API filtering on `payload.chunk_id`, but this is slower and may not be suitable for high-throughput production workloads.

This contract keeps the gateway DB-agnostic and compatible with any client dataset as long as embeddings are uploaded with this payload scheme.
