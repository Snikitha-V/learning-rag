const express = require('express');
const axios = require('axios');
const cookieParser = require('cookie-parser');
const { v4: uuidv4 } = require('uuid');
const Redis = require('ioredis');
const clientProm = require('prom-client');
const crypto = require('crypto');

const PORT = process.env.PORT || 3000;
const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const QDRANT = process.env.QDRANT_URL || 'http://localhost:6333';
const COLLECTION = process.env.QDRANT_COLLECTION || 'learning_chunks';
const SESSION_TTL_SEC = parseInt(process.env.SESSION_TTL_SEC || '900', 10); // 15 min

// payload cache config
// Simple in-memory LRU with TTL to avoid an external dependency; small and deterministic.
class SimpleLRU {
  constructor({ max = 1000, ttl = 300000 } = {}) {
    this.max = max;
    this.ttl = ttl;
    this.map = new Map();
  }
  get(key) {
    const entry = this.map.get(key);
    if (!entry) return undefined;
    if (Date.now() > entry.exp) {
      this.map.delete(key);
      return undefined;
    }
    // refresh recency
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.val;
  }
  set(key, val) {
    const exp = Date.now() + this.ttl;
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, { val, exp });
    while (this.map.size > this.max) {
      const firstKey = this.map.keys().next().value;
      this.map.delete(firstKey);
    }
  }
}

const PAYLOAD_CACHE_MAX = parseInt(process.env.PAYLOAD_CACHE_MAX || '1000', 10);
const PAYLOAD_CACHE_TTL_SEC = parseInt(process.env.PAYLOAD_CACHE_TTL_SEC || '300', 10);
const payloadCache = new SimpleLRU({ max: PAYLOAD_CACHE_MAX, ttl: PAYLOAD_CACHE_TTL_SEC * 1000 });

const app = express();
app.use(express.json());
app.use(cookieParser());

// Session store: use Redis when REDIS_URL set, else in-memory
const REDIS_URL = process.env.REDIS_URL || null;
let redis = null;
if (REDIS_URL) {
  redis = new Redis(REDIS_URL);
}

const sessions = new Map();

function makeSessionLocal() {
  return { state: { active_entity_id: null, active_entity_type: null, active_entity_name: null }, expiresAt: Date.now() + SESSION_TTL_SEC * 1000 };
}

async function getSession(req, res) {
  let sid = req.header('x-session-id') || req.cookies.sessionId;
  if (!sid) {
    sid = uuidv4();
    res.set('X-Session-Id', sid);
    res.cookie('sessionId', sid, { httpOnly: true });
  }

  if (redis) {
    const key = `session:${sid}`;
    const raw = await redis.get(key);
    if (raw) {
      await redis.expire(key, SESSION_TTL_SEC);
      return { sid, state: JSON.parse(raw) };
    }
    const state = { active_entity_id: null, active_entity_type: null, active_entity_name: null };
    await redis.setex(key, SESSION_TTL_SEC, JSON.stringify(state));
    return { sid, state };
  } else {
    if (!sessions.has(sid)) sessions.set(sid, makeSessionLocal());
    const sess = sessions.get(sid);
    sess.expiresAt = Date.now() + SESSION_TTL_SEC * 1000;
    return { sid, state: sess.state };
  }
}

async function saveSession(sid, state) {
  if (redis) {
    const key = `session:${sid}`;
    await redis.setex(key, SESSION_TTL_SEC, JSON.stringify(state));
  } else {
    if (!sessions.has(sid)) sessions.set(sid, makeSessionLocal());
    sessions.get(sid).state = state;
    sessions.get(sid).expiresAt = Date.now() + SESSION_TTL_SEC * 1000;
  }
}

function isContextDependent(q) {
  if (!q) return false;
  // Only treat singular pronouns as context-dependent rewrites.
  // Avoid rewriting plural pronouns like 'they', 'them', 'those' which often refer
  // to multiple items and produce incorrect single-entity rewrites (e.g., "describe them").
  const hasSingularPronoun = /\b(it|this|that|its)\b/i.test(q);
  const isShort = q.trim().split(/\s+/).length <= 7;
  return hasSingularPronoun || isShort;
}

function rewriteQuery(q, name) {
  if (!q || !name) return q;
  return q.replace(/\b(it|this|that|they|those|its)\b/gi, name);
}

// Reproduce Java's UUID.nameUUIDFromBytes(byte[]) behavior (MD5-based name UUID v3)
function javaNameUUIDFromBytes(name) {
  const md5 = crypto.createHash('md5').update(Buffer.from(name, 'utf8')).digest();
  // md5 is 16 bytes
  const bytes = Buffer.from(md5);
  // set version to 3
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  // set variant to RFC4122
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.substr(0,8)}-${hex.substr(8,4)}-${hex.substr(12,4)}-${hex.substr(16,4)}-${hex.substr(20,12)}`;
}

// Basic liveness
app.get('/health', (req, res) => res.json({ status: 'ok' }));

// Readiness: check backend and qdrant are reachable
app.get('/ready', async (req, res) => {
  try {
    const b = await axios.get(`${BACKEND}/actuator/health`, { timeout: 3000 }).catch(() => null);
    const q = await axios.get(`${QDRANT}/collections`, { timeout: 3000 }).catch(() => null);
    if (b && (b.status === 200 || b.status === 204) && q && q.status === 200) return res.json({ ready: true });
    return res.status(503).json({ ready: false });
  } catch (e) {
    return res.status(503).json({ ready: false });
  }
});

// Prometheus metrics
const collectDefaultMetrics = clientProm.collectDefaultMetrics;
collectDefaultMetrics();
const rewriteCounter = new clientProm.Counter({ name: 'gateway_rewrite_total', help: 'Total rewritten queries' });
const stateUpdateCounter = new clientProm.Counter({ name: 'gateway_state_update_total', help: 'Total session state updates' });
const qdrantLookupCounter = new clientProm.Counter({ name: 'gateway_qdrant_lookup_total', help: 'Total qdrant lookups' });

app.get('/metrics', async (req, res) => {
  res.set('Content-Type', clientProm.register.contentType);
  res.end(await clientProm.register.metrics());
});

app.post('/api/v1/query', async (req, res) => {
  const { sid, state } = await getSession(req, res);
  let originalQuery = req.body && req.body.query ? req.body.query : '';
  let queryToSend = originalQuery;

  try {
    if (isContextDependent(originalQuery) && state.active_entity_name) {
      queryToSend = rewriteQuery(originalQuery, state.active_entity_name);
      rewriteCounter.inc();
    }

    // forward to backend
    const headers = {};
    // forward x-api-key if present
    if (req.header('x-api-key')) headers['x-api-key'] = req.header('x-api-key');

    const backendResp = await axios.post(`${BACKEND}/api/v1/query`, { query: queryToSend }, { headers, timeout: 120000 });

    const data = backendResp.data || {};

    // If backend provided sources, fetch payloads for top sources and update session state.
    const sources = Array.isArray(data.sources) ? data.sources : [];
    if (sources.length > 0) {
      try {
        qdrantLookupCounter.inc();
        // fetch payloads for up to first 5 sources to build context
        const maxFetch = 5;
        const fetched = [];
        for (let i = 0; i < Math.min(maxFetch, sources.length); i++) {
          const src = sources[i];
          const pointId = javaNameUUIDFromBytes(src);
          let payload = payloadCache.get(pointId);
          if (!payload) {
            try {
              const qresp = await axios.get(`${QDRANT}/collections/${COLLECTION}/points/${pointId}?with_payload=true`, { timeout: 8000 });
              payload = (qresp.data && qresp.data.result && qresp.data.result.payload) || null;
              if (!payload) throw new Error('no-payload');
              payloadCache.set(pointId, payload);
            } catch (errFetch) {
              // fallback to scroll by chunk_id
              try {
                const body = { filter: { must: [ { key: 'chunk_id', match: { value: src } } ] }, limit: 1 };
                const qresp2 = await axios.post(`${QDRANT}/collections/${COLLECTION}/points/scroll`, body, { timeout: 10000 });
                const pts = (qresp2.data && qresp2.data.result) || [];
                if (pts.length > 0 && pts[0].payload) {
                  payload = pts[0].payload;
                  payloadCache.set(pointId, payload);
                }
              } catch (e2) {
                // ignore fetch failures for this source
              }
            }
          }
          if (payload) fetched.push(payload);
        }

        // prefer the first course-type payload if present, else fall back to first payload
        let chosen = null;
        // simple heuristic: if any fetched payload is chunk_type=='COURSE', choose it
        for (const p of fetched) if (p && p.chunk_type === 'COURSE') { chosen = p; break; }
        if (!chosen && fetched.length > 0) chosen = fetched[0];

        // store last sources in session (for plural/clarity handling later)
        state.last_sources = sources.slice(0, maxFetch);
        state.last_payloads = fetched;

        // If chosen is a CLASS, attempt to resolve parent course using metadata
        let resolvedCourse = null;
        if (chosen && chosen.chunk_type === 'CLASS' && chosen.metadata) {
          // metadata may include course_chunk_id or course_id
          const m = chosen.metadata;
          if (m.course_chunk_id) {
            // fetch by that chunk id
            const cidPoint = javaNameUUIDFromBytes(m.course_chunk_id);
            try {
              const qrespC = await axios.get(`${QDRANT}/collections/${COLLECTION}/points/${cidPoint}?with_payload=true`, { timeout: 8000 });
              resolvedCourse = (qrespC.data && qrespC.data.result && qrespC.data.result.payload) || null;
            } catch (e) {
              // ignore
            }
          } else if (m.course_id !== undefined) {
            // search for a COURSE payload with metadata.course_id == m.course_id
            try {
              const body = { filter: { must: [ { key: 'chunk_type', match: { value: 'COURSE' } }, { key: 'metadata', nested: { key: 'course_id', match: { value: m.course_id } } } ] }, limit: 1 };
              // Note: some Qdrant versions require filter structure; fall back to simple scroll if this fails
              const qrespC2 = await axios.post(`${QDRANT}/collections/${COLLECTION}/points/scroll`, body, { timeout: 10000 });
              const pts = (qrespC2.data && qrespC2.data.result) || [];
              if (pts.length > 0) resolvedCourse = pts[0].payload;
            } catch (e) {
              // ignore
            }
          }
        }

        // update session active entity using chosen payload
        if (chosen) {
          state.active_entity_id = chosen.chunk_id || state.active_entity_id;
          state.active_entity_name = chosen.title || chosen.chunk_id || state.active_entity_name;
          state.active_entity_type = chosen.chunk_type || state.active_entity_type || 'UNKNOWN';
          if (resolvedCourse) {
            // record resolved course in session for use in future rewrites
            state.active_course = { id: resolvedCourse.chunk_id || null, title: resolvedCourse.title || null };
          }
          stateUpdateCounter.inc();
          await saveSession(sid, state);
        }

        // If the incoming query seems to ask about the 'course' related to prior class, rewrite to target the course
        const asksCourse = /\b(what course|which course|course is this related to|related to which course)\b/i.test(originalQuery);
        if (asksCourse && !isContextDependent(originalQuery) && state.active_course && state.active_course.title) {
          // First, try backend SQL endpoint to get authoritative schedule for the course
          try {
            const schedResp = await axios.get(`${BACKEND}/api/v1/sql/course-schedule`, { params: { title: state.active_course.title }, timeout: 8000 });
            if (schedResp.data && schedResp.data.found && schedResp.data.range) {
              const r = schedResp.data.range;
              let ans;
              if (r.earliest && r.latest) {
                if (r.earliest === r.latest) ans = `${state.active_course.title} was taught on ${r.earliest}.`;
                else ans = `${state.active_course.title} was taught between ${r.earliest} and ${r.latest}.`;
              } else if (r.earliest) ans = `${state.active_course.title} was first taught on ${r.earliest}.`;
              else if (r.latest) ans = `${state.active_course.title} was last taught on ${r.latest}.`;
              else ans = `No schedule data available for ${state.active_course.title}.`;

              return res.json({ answer: ans, sources: [ `SQL:${schedResp.data.course_code || state.active_course.id}` ], context: { active_entity: state.active_course.title, entity_type: 'COURSE' } });
            }
          } catch (e) {
            // ignore and fall back to rewrite+backend
          }

          // fallback: rewrite to use course title and let backend handle it
          queryToSend = rewriteQuery(originalQuery, state.active_course.title);
          rewriteCounter.inc();
          const backendResp2 = await axios.post(`${BACKEND}/api/v1/query`, { query: queryToSend }, { headers, timeout: 120000 });
          const data2 = backendResp2.data || {};
          const out2 = Object.assign({}, data2, { context: { active_entity: state.active_course.title, entity_type: 'COURSE' } });
          return res.json(out2);
        }

      } catch (e) {
        console.warn('Qdrant lookup failed', e.message);
      }
    }

    // Optionally augment response with context for UI transparency
    const out = Object.assign({}, data, { context: { active_entity: state.active_entity_name, entity_type: state.active_entity_type } });

    // Return backend response unchanged except optional context
    return res.json(out);
  } catch (err) {
    console.error('Gateway error:', err.message);
    if (err.response && err.response.data) {
      return res.status(err.response.status || 502).json(err.response.data);
    }
    return res.status(500).json({ error: err.message || 'gateway error' });
  }
});

app.listen(PORT, () => console.log(`conversation-gateway listening on ${PORT}, backend=${BACKEND}, qdrant=${QDRANT}`));
