# Optional Tenant Isolation (RLS) and Audit Logging

These steps are optional, off by default, and safe to apply later. They do **not** change application logic. Enable only when you need tenant isolation or write auditing.

## Row Level Security (RLS) for tenant isolation

### What it does
Enforces `tenant_id` at the database layer so one tenant cannot see another tenant’s rows.

### Prerequisites
- A `tenant_id` column on every table you want isolated (e.g., `chunks.tenant_id`).
- The application must set `app.tenant_id` per request/session before running queries.

### How to enable (Postgres)
Run as superuser / admin:

```sql
-- 1) Enable RLS on the table(s)
ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;

-- 2) Add a policy that matches the current tenant
CREATE POLICY tenant_policy_chunks
ON chunks
USING (tenant_id = current_setting('app.tenant_id', true));
```

### How the app should set tenant
Before any query for that tenant, set the runtime parameter:

```sql
SET app.tenant_id = '<tenant-id-here>';
```

If using a connection pool, ensure this is set per-request (or via a request-scoped interceptor) so pooled connections don’t leak tenant context.

### Safety checklist
- ✅ With RLS on, tenant A cannot read tenant B rows.
- ⚠️ If `app.tenant_id` is not set, queries will return zero rows (by policy), which is safer than leaking data.
- ❌ Do not disable RLS once enabled; instead, adjust policies.

## Audit logging (writes)

### Option 1: Simple built-in logging
In `postgresql.conf` (or via ALTER SYSTEM / environment for your managed Postgres):

```
log_statement = 'mod'   # logs INSERT/UPDATE/DELETE, not SELECT
log_line_prefix = '%m %u %d %h '
```

- Restart Postgres (or reload if supported) to apply.
- Logs will capture who did the write and when. Use log rotation to manage size.

### Option 2: pgAudit (richer auditing)
- Install/enable the `pgaudit` extension (depends on your Postgres distribution/managed service).
- Configure `shared_preload_libraries = 'pgaudit'` and set `pgaudit.log = 'write'` (and other classes as needed).
- Restart Postgres to load the extension.

### Safety checklist
- ✅ Reader role remains read-only; audits show any attempted writes.
- ✅ Editor/admin writes are traceable for compliance.

## Credentials/roles (recap)
- `rag_reader_user` → use for RAG/LLM paths (read-only).
- `rag_editor_user` → use for admin/upload (write allowed on `chunks`).
- `rag_admin` → DB maintenance.

## Shipping guidance
- Keep RLS and audit **disabled by default**; enable per-customer via these DB-side steps.
- No code changes are required; only DB DDL/config and a per-request `SET app.tenant_id` when you turn on RLS.
- Document the tenant ID you set per customer and verify with a quick `SELECT` to ensure only that tenant’s rows are visible.
