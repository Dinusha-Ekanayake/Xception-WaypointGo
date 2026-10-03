import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createMcpServer, type ToolCallLog } from '../src/server.ts';
import { BackendClient } from '../src/client.ts';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { saveConnection } from '../src/credentials.ts';

async function fixture(actions = ['order:Read'], options: { redirect?: boolean; oversized?: boolean } = {}) {
  let allowed = true;
  const logs: ToolCallLog[] = [];
  const requests: string[] = [];
  const http = createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer mcp.test');
    requests.push(req.url ?? '');
    res.setHeader('content-type', 'application/json');
    if (!allowed) {
      res.writeHead(403, { 'content-type': 'application/problem+json' });
      res.end(JSON.stringify({ code: 'FORBIDDEN', detail: 'denied', correlationId: 'test-correlation', violations: [] }));
    } else if (req.url === '/api/mcp/context') {
      res.end(JSON.stringify({ userId: '11111111-1111-4111-8111-111111111111', roles: ['store_manager'], scope: ['outlet:OUT001'], readActions: actions }));
    } else if (options.redirect) {
      res.writeHead(302, { location: '/api/commands' });
      res.end();
    } else if (options.oversized) {
      res.end('x'.repeat(300_000));
    } else {
      res.end(JSON.stringify({ items: [{ orderId: '22222222-2222-4222-8222-222222222222', orderRef: 'WPT-test', outletId: 'OUT001', depotCode: 'PEL', brandCode: 'Fresh', districtName: 'Colombo', requestedDate: '2026-10-02', deliveryDate: '2026-10-02', dateRolled: false, temperature: 'ambient', itemCount: 1, weightKg: 5, volumeM3: 0.2, status: 'CONFIRMED', deferralCount: 0, placedAt: '2026-10-02T01:00:00Z', rowVersion: 1, password: 'never-return', email: 'private@test', lines: [] }], nextCursor: 'next-page', token: 'never-return' }));
    }
  });
  await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
  const address = http.address();
  assert.ok(address && typeof address !== 'string');
  const server = createMcpServer(new BackendClient(`http://127.0.0.1:${address.port}`, 'mcp.test'), entry => logs.push(entry));
  const client = new Client({ name: 'waypoint-test-client', version: '1.0.0' });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  return { client, requests, logs, origin: `http://127.0.0.1:${address.port}`, revoke: () => { allowed = false; }, close: async () => {
    await client.close();
    await server.close();
    http.closeAllConnections();
    await new Promise<void>(resolve => http.close(() => resolve()));
  } };
}

test('SDK discovery exposes only eligible read tools and typed schemas', async () => {
  const f = await fixture();
  try {
    const { tools } = await f.client.listTools();
    assert.deepEqual(tools.map(t => t.name).sort(), ['get_order', 'list_orders', 'my_context']);
    for (const tool of tools) {
      assert.equal(tool.annotations?.readOnlyHint, true);
      assert.equal(tool.annotations?.destructiveHint, false);
      assert.equal(tool.inputSchema.additionalProperties, false);
      assert.ok(tool.outputSchema);
    }
  } finally { await f.close(); }
});

test('stdio child connects through the official SDK without credentials in tool output', async () => {
  const f = await fixture();
  const dir = await mkdtemp(join(tmpdir(), 'waypoint-stdio-test-'));
  const file = join(dir, 'connection.json');
  const client = new Client({ name: 'waypoint-stdio-test', version: '1.0.0' });
  try {
    await saveConnection(file, { backendUrl: f.origin, token: 'mcp.test' });
    const env: Record<string, string> = {};
    for (const [key, value] of Object.entries(process.env)) if (value !== undefined) env[key] = value;
    env.WAYPOINT_MCP_CREDENTIAL_FILE = file;
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: [fileURLToPath(new URL('../dist/stdio.js', import.meta.url))], env }));
    const { tools } = await client.listTools();
    assert.ok(tools.some(t => t.name === 'list_orders'));
    const result = await client.callTool({ name: 'my_context', arguments: {} });
    assert.ok(JSON.stringify(result).includes('OUT001'));
    assert.ok(!JSON.stringify(result).includes('mcp.test'));
  } finally { await client.close(); await f.close(); await rm(dir, { recursive: true }); }
});

test('interleaved users have independent discovery and responses', async () => {
  const a = await fixture(['order:Read']);
  const b = await fixture(['audit:Read']);
  try {
    const [orders, audit] = await Promise.all([a.client.listTools(), b.client.listTools()]);
    assert.ok(orders.tools.some(t => t.name === 'list_orders'));
    assert.ok(!orders.tools.some(t => t.name === 'list_audit'));
    assert.ok(audit.tools.some(t => t.name === 'list_audit'));
    assert.ok(!audit.tools.some(t => t.name === 'list_orders'));
    a.revoke();
    assert.equal((await a.client.callTool({ name: 'my_context', arguments: {} })).isError, true);
    assert.equal((await b.client.callTool({ name: 'my_context', arguments: {} })).isError, undefined);
  } finally { await a.close(); await b.close(); }
});

test('SDK order read preserves cursor and authoritative totals while discarding unknown sensitive fields', async () => {
  const f = await fixture();
  try {
    const result = await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001', cursor: 'previous-page', limit: 5 } });
    assert.equal(result.isError, undefined);
    const text = JSON.stringify(result);
    assert.ok(text.includes('next-page'));
    assert.ok(text.includes('weightKg'));
    assert.ok(text.includes('inferred_unverified_sku'));
    assert.ok(!text.includes('never-return'));
    assert.ok(!text.includes('private@test'));
    assert.ok(f.requests.some(p => p.includes('cursor=previous-page') && p.includes('limit=5')));
  } finally { await f.close(); }
});

test('existing connection rechecks authorization after revocation', async () => {
  const f = await fixture();
  try {
    await f.client.listTools();
    f.revoke();
    const result = await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001' } });
    assert.equal(result.isError, true);
    assert.ok(JSON.stringify(result).includes('FORBIDDEN'));
    assert.ok(JSON.stringify(result).includes('test-correlation'));
  } finally { await f.close(); }
});

test('invalid fields and unknown tools cannot select endpoints or send writes', async () => {
  const f = await fixture();
  try {
    const result = await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001', url: '/api/commands' } });
    assert.equal(result.isError, true);
    const unknown = await f.client.callTool({ name: 'publish_plan', arguments: {} });
    assert.equal(unknown.isError, true);
    assert.ok(f.requests.every(p => p === '/api/mcp/context'));
  } finally { await f.close(); }
});

for (const [name, options] of [['redirect', { redirect: true }], ['oversized', { oversized: true }]] as const) {
  test(`${name} responses fail visibly without forwarding credentials or truncating`, async () => {
    const f = await fixture(['order:Read'], options);
    try {
      const result = await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001' } });
      assert.equal(result.isError, true);
      assert.ok(!f.requests.includes('/api/commands'));
      assert.ok(!JSON.stringify(result).includes('mcp.test'));
    } finally { await f.close(); }
  });
}

const TRIP_ID = '33333333-3333-4333-8333-333333333333';
const DELIVERY_ID = '44444444-4444-4444-8444-444444444444';
const ORDER_ID = '55555555-5555-4555-8555-555555555555';
const RECEIPT_ID = '66666666-6666-4666-8666-666666666666';

async function discoveryFixture(actions: string[]) {
  const requests: string[] = [];
  const http = createServer((req, res) => {
    requests.push(req.url ?? '');
    res.setHeader('content-type', 'application/json');
    const url = req.url ?? '';
    if (url === '/api/mcp/context') {
      res.end(JSON.stringify({ userId: '11111111-1111-4111-8111-111111111111', roles: ['dispatcher'],
        scope: ['depot:PEL'], readActions: actions }));
      return;
    }
    if (url.startsWith('/api/loading/trips?')) {
      res.end(JSON.stringify([{ tripId: TRIP_ID, vehicleId: 'VEH001', tripNumber: 1, tripsForVehicle: 1,
        plannedDeparture: '04:00', status: 'READY', brandCode: 'Fresh', districtName: 'Colombo',
        temperature: 'ambient', dockCode: 'Dock 1', stopCount: 1, orderCount: 1,
        weightKg: 100, volumeM3: 2, rowVersion: 1,
        holder: { userId: '99999999-9999-4999-8999-999999999999' }, password: 'never-return' }]));
      return;
    }
    if (url.startsWith('/api/execution/run-sheets?')) {
      res.end(JSON.stringify([{ vehicleId: 'VEH001', serviceDate: '2026-10-02',
        stops: [{ deliveryId: DELIVERY_ID, tripId: TRIP_ID, sequence: 1, orderId: ORDER_ID,
          outletId: 'OUT001', itemCount: 1, plannedArrival: '05:00', windowOpen: '04:00',
          windowClose: '08:00', outcome: 'DELIVERED', proofCaptured: true,
          lines: [{ productId: 'P1', orderedUnits: 1 }], secret: 'never-return' }] }]));
      return;
    }
    if (url.startsWith('/api/receipts/pending?')) {
      res.end(JSON.stringify([{ orderId: ORDER_ID, deliveryId: DELIVERY_ID,
        outletId: 'OUT001', deliveredAt: '2026-10-02T03:00:00Z' }]));
      return;
    }
    if (url === `/api/receipts/${ORDER_ID}/custody`) {
      res.end(JSON.stringify({ orderId: ORDER_ID,
        receipt: { receiptId: RECEIPT_ID, orderId: ORDER_ID, deliveryId: DELIVERY_ID,
          outletId: 'OUT001', status: 'CONFIRMED', confirmedAt: '2026-10-02T04:00:00Z',
          rowVersion: 1, depotCode: 'PEL', deliveredAt: '2026-10-02T03:00:00Z',
          autoClosesAt: '2026-10-09T03:00:00Z', late: false,
          lines: [{ productId: 'P1', expectedQuantity: 1, receivedQuantity: 1 }],
          note: 'never-return', confirmedBy: '99999999-9999-4999-8999-999999999999' },
        delivery: { deliveryId: DELIVERY_ID, tripId: TRIP_ID, completedAt: '2026-10-02T03:05:00Z',
          deliveredUnits: 1, recordedBy: '99999999-9999-4999-8999-999999999999' },
        loadingCheck: { orderId: ORDER_ID, outletId: 'OUT001', status: 'LOADED',
          loadedUnits: 1, attempt: 1 },
        proof: { deliveryId: DELIVERY_ID, orderId: ORDER_ID, tripId: TRIP_ID,
          outletId: 'OUT001', vehicleId: 'VEH001', serviceDate: '2026-10-02',
          outcome: 'DELIVERED', timingUncertain: false, lowEvidence: false,
          serverRecordedAt: '2026-10-02T03:06:00Z', rowVersion: 1 },
        unavailable: [] }));
      return;
    }
    res.writeHead(404); res.end(JSON.stringify({ code: 'NOT_FOUND' }));
  });
  await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
  const address = http.address();
  assert.ok(address && typeof address !== 'string');
  const server = createMcpServer(new BackendClient(`http://127.0.0.1:${address.port}`, 'mcp.test'));
  const client = new Client({ name: 'waypoint-discovery-test', version: '1.0.0' });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  return { client, requests, close: async () => {
    await client.close();
    await server.close();
    http.closeAllConnections();
    await new Promise<void>(resolve => http.close(() => resolve()));
  } };
}

test('discovery exposes work queues and custody only with their read actions', async () => {
  const full = await discoveryFixture(['loading:Read', 'delivery:Read', 'receipt:Read']);
  try {
    const { tools } = await full.client.listTools();
    for (const name of ['list_ready_trips', 'list_run_sheets', 'list_pending_receipts', 'get_custody']) {
      assert.ok(tools.some(t => t.name === name), name);
    }
  } finally { await full.close(); }
  const none = await discoveryFixture(['order:Read']);
  try {
    const { tools } = await none.client.listTools();
    for (const name of ['list_ready_trips', 'list_run_sheets', 'list_pending_receipts', 'get_custody']) {
      assert.ok(!tools.some(t => t.name === name), name);
    }
  } finally { await none.close(); }
});

test('work discovery returns bounded IDs and discards sensitive neighbours', async () => {
  const f = await discoveryFixture(['loading:Read', 'delivery:Read', 'receipt:Read']);
  try {
    const trips = await f.client.callTool({ name: 'list_ready_trips', arguments: { depot: 'PEL', date: '2026-10-02' } });
    assert.equal(trips.isError, undefined);
    assert.ok(JSON.stringify(trips).includes(TRIP_ID));
    assert.ok(!JSON.stringify(trips).includes('never-return'));
    assert.ok(!JSON.stringify(trips).includes('99999999'));
    assert.ok(f.requests.some(p => p.includes('/api/loading/trips?') && p.includes('depot=PEL')));
    const sheets = await f.client.callTool({ name: 'list_run_sheets', arguments: { date: '2026-10-02' } });
    assert.equal(sheets.isError, undefined);
    assert.ok(JSON.stringify(sheets).includes(DELIVERY_ID));
    assert.ok(!JSON.stringify(sheets).includes('never-return'));
    const pending = await f.client.callTool({ name: 'list_pending_receipts', arguments: { outlet: 'OUT001' } });
    assert.equal(pending.isError, undefined);
    assert.ok(JSON.stringify(pending).includes(ORDER_ID));
    const custody = await f.client.callTool({ name: 'get_custody', arguments: { orderId: ORDER_ID } });
    assert.equal(custody.isError, undefined);
    const text = JSON.stringify(custody);
    assert.ok(text.includes(RECEIPT_ID));
    assert.ok(text.includes(DELIVERY_ID));
    assert.ok(text.includes('inferred_unverified_sku'));
    assert.ok(!text.includes('never-return'));
    assert.ok(!text.includes('99999999'));
    const bad = await f.client.callTool({ name: 'list_ready_trips', arguments: { depot: 'PEL', date: 'not-a-date' } });
    assert.equal(bad.isError, true);
  } finally { await f.close(); }
});

test('every tool call logs its tool, outcome and duration, never its arguments or result', async () => {
  const f = await fixture();
  try {
    await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001', cursor: 'secret-cursor', limit: 5 } });
    await f.client.callTool({ name: 'made_up_tool_name', arguments: {} });
    f.revoke();
    await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001' } });
    assert.deepEqual(f.logs.map(l => [l.tool, l.outcome, l.status]), [
      ['list_orders', 'ok', 200],
      ['unknown', 'UNKNOWN_TOOL', 400],
      ['list_orders', 'FORBIDDEN', 403],
    ]);
    assert.equal(f.logs[2]!.correlationId, 'test-correlation', 'joins the backend audit rows');
    assert.ok(f.logs.every(l => l.event === 'mcp.tool_call' && Number.isInteger(l.durationMs) && l.durationMs >= 0));
    const text = JSON.stringify(f.logs);
    for (const leaked of ['secret-cursor', 'made_up_tool_name', 'never-return', 'mcp.test', 'OUT001']) assert.ok(!text.includes(leaked), leaked);
  } finally { await f.close(); }
});

test('an oversized backend answer is logged as such', async () => {
  const f = await fixture(['order:Read'], { oversized: true });
  try {
    await f.client.callTool({ name: 'list_orders', arguments: { outlet: 'OUT001' } });
    assert.equal(f.logs.at(-1)!.tool, 'list_orders');
    assert.notEqual(f.logs.at(-1)!.outcome, 'ok');
  } finally { await f.close(); }
});
