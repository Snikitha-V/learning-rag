# Conversation Gateway (context-aware RAG)

Lightweight gateway that makes the existing RAG service context-aware without any backend changes.

Features:
- Detects context-dependent (follow-up) queries
- Rewrites queries by inserting the last resolved entity name
- Forwards requests to existing backend `POST /api/v1/query`
- Updates session ConversationState using Qdrant payloads (top source)
- Returns backend response unchanged (optionally augments with `context`)

Quick start

1. Create a `.env` or export environment variables:

```
export BACKEND_URL=http://localhost:8080
export QDRANT_URL=http://localhost:6333
export QDRANT_COLLECTION=learning_chunks
export PORT=3000
```

2. Install dependencies and run:

```bash
cd conversation-gateway
npm install
npm start
```

3. Point your client to `http://localhost:3000/api/v1/query` instead of the Java service. The gateway will forward requests to the Java backend, rewriting follow-ups when appropriate.

Notes

- Sessions are stored in-memory with TTL (default 900s). For multi-instance deployments, use Redis and modify the code to persist session state.
- The gateway expects the vector DB payload contract (see `../PAYLOAD_CONTRACT.md`).
- Forward `x-api-key` header to the backend for protected APIs.

Security

- Cookies are set `httpOnly` for session id. In production, use secure cookies and HTTPS.

Extending

- Add Redis instead of in-memory Map for scaling.
- Add logging/metrics (Prometheus/OpenTelemetry) as needed.
