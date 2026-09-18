// Talks to Rocoto from Node.js 18+: built-in fetch for read/bulk/exec and Server-Sent Events,
// the `ws` package for the WebSocket endpoint (the WebSocket API built into browsers and Node
// cannot send an Authorization header).
//
// Environment: ROCOTO_URL (default http://localhost:8080/rocoto) and either ROCOTO_TOKEN or
// ROCOTO_USER + ROCOTO_PASSWORD.
import WebSocket from 'ws';

const BASE = (process.env.ROCOTO_URL ?? 'http://localhost:8080/rocoto').replace(/\/$/, '');
const ORDERS = 'com.example.demo:type=Orders';

function authorization() {
  const { ROCOTO_TOKEN, ROCOTO_USER, ROCOTO_PASSWORD } = process.env;
  if (ROCOTO_TOKEN) return `Bearer ${ROCOTO_TOKEN}`;
  if (ROCOTO_USER && ROCOTO_PASSWORD) {
    return 'Basic ' + Buffer.from(`${ROCOTO_USER}:${ROCOTO_PASSWORD}`, 'utf8').toString('base64');
  }
  throw new Error('Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD');
}
const AUTH = authorization();

// Rocoto reports errors as JSON: {"status":403,"error":"Access denied by policy"}
async function check(response) {
  if (response.ok) return response.json();
  const text = await response.text();
  let message = text;
  try { message = JSON.parse(text).error; } catch { /* not JSON */ }
  const error = new Error(`HTTP ${response.status}: ${message}`);
  error.status = response.status;
  error.retryAfter = response.headers.get('Retry-After'); // set on 429 (lockout)
  throw error;
}

// Resource style: the ObjectName is ONE URL-encoded path segment.
async function read(mbean, attribute) {
  const url = `${BASE}/api/v1/mbeans/${encodeURIComponent(mbean)}/attributes/${encodeURIComponent(attribute)}`;
  return (await check(await fetch(url, { headers: { Authorization: AUTH } }))).value;
}

// RPC style. The Content-Type is mandatory: anything but application/json is answered with 415.
async function rpc(kind, body) {
  return check(await fetch(`${BASE}/api/v1/${kind}`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

// Server-Sent Events over fetch streaming. EventSource cannot send an Authorization header.
async function* subscribe(mbean, attribute, { interval = 1000, signal } = {}) {
  const query = new URLSearchParams({ mbean, attribute, interval: String(interval), onChange: 'false' });
  const response = await fetch(`${BASE}/api/v1/subscribe?${query}`, {
    headers: { Authorization: AUTH, Accept: 'text/event-stream' }, signal,
  });
  if (!response.ok) await check(response); // denied or unknown targets fail before the stream starts
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let end;
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = 'message';
      let data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event: ')) event = line.slice(7);
        else if (line.startsWith('data: ')) data += line.slice(6);
      }
      if (data) yield { event, data: JSON.parse(data) };
    }
  }
}

function webSocketValues(count) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(BASE.replace(/^http/, 'ws') + '/api/v1/ws', {
      headers: { Authorization: AUTH }, // the upgrade request is authenticated like any other
    });
    let seen = 0;
    socket.on('open', () => socket.send(JSON.stringify({
      action: 'subscribe', id: 'threads', mbean: 'java.lang:type=Threading',
      attributes: ['ThreadCount'], interval: 1000, onChange: false,
    })));
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString());
      console.log(message.id, message.event, message.data);
      if (++seen >= count) {
        socket.send(JSON.stringify({ action: 'unsubscribe', id: 'threads' }));
        socket.close(1000, 'done');
      }
    });
    socket.on('unexpected-response', (_req, res) => reject(new Error(`WebSocket upgrade failed: HTTP ${res.statusCode}`)));
    socket.on('error', reject);
    socket.on('close', resolve);
  });
}

console.log('== who am I');
console.log((await check(await fetch(`${BASE}/api/v1/server`, { headers: { Authorization: AUTH } }))).caller);

console.log('== read');
console.log('heap used:', (await read('java.lang:type=Memory', 'HeapMemoryUsage')).used);
console.log(await rpc('read', { mbean: ORDERS, attributes: ['OrdersProcessed', 'QueueDepth', 'DbPassword'] }));

console.log('== bulk: every item carries its own status');
const bulk = await rpc('bulk', [
  { type: 'read', mbean: 'java.lang:type=Threading', attribute: 'ThreadCount' },
  { type: 'read', mbean: 'java.lang:type=Runtime', attribute: 'DoesNotExist' },
  { type: 'search', pattern: 'java.lang:type=GarbageCollector,*' },
]);
for (const item of bulk.results) console.log(item.status, item.value ?? item.mbeans ?? item.error);

console.log("== exec (needs an allow rule for the caller's role)");
try {
  console.log(await rpc('exec', { mbean: ORDERS, operation: 'drainQueue', arguments: [5] }));
} catch (e) {
  console.log('failed:', e.message);
}

console.log('== error handling');
try {
  await read('nope:type=None', 'X');
} catch (e) {
  console.log('failed:', e.message);
}

console.log('== Server-Sent Events: three values of QueueDepth');
const abort = new AbortController();
let seen = 0;
try {
  for await (const { event, data } of subscribe(ORDERS, 'QueueDepth', { signal: abort.signal })) {
    console.log(event, data);
    if ((event === 'value' || event === 'error') && ++seen >= 3) break;
  }
} finally {
  abort.abort(); // closes the connection = unsubscribes
}

console.log('== WebSocket: three values of ThreadCount');
await webSocketValues(3);
