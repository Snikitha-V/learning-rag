const express = require('express');
const axios = require('axios');
const { spawn } = require('child_process');

const BACKEND_PORT = 5000;
const QDRANT_PORT = 6000;
const GATEWAY_PORT = 3010;

async function startMockBackend() {
  const app = express();
  app.use(express.json());

  let lastReceived = null;

  app.post('/api/v1/query', (req, res) => {
    lastReceived = req.body && req.body.query ? req.body.query : '';
    // Return a sources array referencing TOPIC-11 for first-turn entity
    const reply = {
      answer: `backend received: ${lastReceived}`,
      sources: lastReceived.toLowerCase().includes('databases and sql') ? ['TOPIC-11'] : ['TOPIC-11']
    };
    res.json(reply);
  });

  const server = app.listen(BACKEND_PORT);
  return { server, getLast: () => lastReceived };
}

async function startMockQdrant() {
  const app = express();
  app.use(express.json());

  app.post('/collections/learning_chunks/points/scroll', (req, res) => {
    const match = req.body && req.body.filter && req.body.filter.must && req.body.filter.must[0];
    const value = match && match.match && match.match.value;
    if (value === 'TOPIC-11') {
      return res.json({ result: [ { id: 'uuid-topic-11', payload: { chunk_id: 'TOPIC-11', title: 'Databases and SQL', chunk_type: 'COURSE' } } ] });
    }
    return res.json({ result: [] });
  });

  const server = app.listen(QDRANT_PORT);
  return { server };
}

function startGateway() {
  const env = Object.assign({}, process.env, {
    BACKEND_URL: `http://localhost:${BACKEND_PORT}`,
    QDRANT_URL: `http://localhost:${QDRANT_PORT}`,
    QDRANT_COLLECTION: 'learning_chunks',
    PORT: String(GATEWAY_PORT),
    SESSION_TTL_SEC: '300'
  });

  const child = spawn(process.execPath, ['proxy.js'], { cwd: __dirname + '/..', env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', d => process.stdout.write(`[gateway] ${d}`));
  child.stderr.on('data', d => process.stderr.write(`[gateway err] ${d}`));
  return child;
}

async function waitFor(url, timeout=5000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    try {
      const r = await axios.get(url, { timeout: 1000 });
      if (r.status === 200) return true;
    } catch (e) {}
    await new Promise(r=>setTimeout(r,200));
  }
  throw new Error('timeout waiting for ' + url);
}

(async ()=>{
  const backend = await startMockBackend();
  const qdrant = await startMockQdrant();
  const gateway = startGateway();

  try {
    await waitFor(`http://localhost:${GATEWAY_PORT}/health`, 10000);

    // First turn: provide full entity
    let resp1 = await axios.post(`http://localhost:${GATEWAY_PORT}/api/v1/query`, { query: 'Tell me about Databases and SQL' }, { timeout: 5000 });
    console.log('resp1.data=', resp1.data);
    const sessionId = resp1.headers['x-session-id'] || resp1.data && resp1.data.context && resp1.data.context.active_entity ? null : null;

    if (!resp1.data.context || resp1.data.context.active_entity !== 'Databases and SQL') {
      throw new Error('First turn did not set context active_entity');
    }

    // Second turn: follow-up without entity; send same session cookie header
    const headers = {};
    if (resp1.headers['x-session-id']) headers['x-session-id'] = resp1.headers['x-session-id'];

    let resp2 = await axios.post(`http://localhost:${GATEWAY_PORT}/api/v1/query`, { query: 'When is it offered?' }, { headers, timeout: 5000 });
    console.log('resp2.data=', resp2.data);

    // Ensure backend received rewritten query referencing entity
    // Our mock backend echoes what it received; check logs via backend.getLast
    const last = backend.getLast();
    if (!/Databases and SQL/i.test(last)) {
      throw new Error('Gateway did not rewrite follow-up query; backend saw: ' + last);
    }

    console.log('SMOKE TEST PASSED');
    // cleanup
    backend.server.close();
    qdrant.server.close();
    gateway.kill();
    process.exit(0);
  } catch (e) {
    console.error('SMOKE TEST FAILED:', e.message);
    try { backend.server.close(); } catch(e){}
    try { qdrant.server.close(); } catch(e){}
    try { gateway.kill(); } catch(e){}
    process.exit(2);
  }
})();
