Production notes

- For production, set `REDIS_URL` to a managed Redis instance and remove the in-memory session/cache fallback. This enables session persistence across gateway replicas.
- Run the gateway behind TLS and an ingress/load-balancer. Protect `/metrics` behind a metrics endpoint proxy or use mTLS/ACLs.
- Use the provided `Dockerfile` and `docker-compose.gateway.yml` for containerization; deploy as part of your service stack.
- Monitor `/metrics` and add Prometheus alerts for high error rates, excessive rewrite counts, and frequent Qdrant lookup failures.

Deterministic point ids and Qdrant
---------------------------------

The gateway expects points to be upserted with deterministic UUID ids derived from the original `chunk_id`. This enables fast `GET /collections/{col}/points/{id}?with_payload=true` lookups. The ingestion tool (`EmbeddingUploader`) already creates these ids using Java's `UUID.nameUUIDFromBytes(...)` behavior.

Recommended Qdrant settings when upserting:

- Use explicit `id` (UUID strings) when upserting points to make the operation idempotent.
- Configure collection vectors with correct `size` (e.g., 768) and `distance` set to `Cosine` if you normalize vectors on ingestion.
- Upsert with `?wait=true` where possible in batch jobs to ensure availability before traffic.

Cache configuration
-------------------

The gateway includes a tunable in-process LRU cache for payloads to reduce Qdrant lookups. For multi-node deployments, use Redis as a shared cache/session store by configuring `REDIS_URL`.

Environment variables to tune cache/session behavior:

- `PAYLOAD_CACHE_MAX` (default 1000): max entries in the in-process payload cache.
- `PAYLOAD_CACHE_TTL_SEC` (default 300): TTL for cached payload entries in seconds.
- `SESSION_TTL_SEC` (default 900): session TTL for conversation state persistence.

Operational notes
-----------------

- If deterministic ids are not available, the gateway will gracefully fall back to a `scroll` search by `payload.chunk_id` (slower).
- For high throughput, prefer deterministic ids + cache + point-by-id fetch.
- Consider adding rate-limiting and request auth at the ingress layer; the gateway forwards `x-api-key` if present but does not validate it.
