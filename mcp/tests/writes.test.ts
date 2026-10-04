import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type IncomingMessage } from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createMcpServer } from '../src/server.ts';
import { BackendClient } from '../src/client.ts';
import { createHttpHandler, missingScope } from '../src/http.ts';
import { fieldClasses, withholdPersonal } from '../src/outputs.ts';

const USER = '11111111-1111-4111-8111-111111111111';
const ISSUE = '77777777-7777-4777-8777-777777777777';
const COMMAND = '88888888-8888-4888-8888-888888888888';
const ORDER = '99999999-9999-4999-8999-999999999999';

type Context = { grantedScopes?: string[]; writeTools?: string[]; personalFields?: boolean; readActions?: string[] };
type Seen = { method: string; path: string; body: unknown };

const issue = { issueId: ISSUE, type: 'DAMAGED_GOODS', severity: 'LOW', status: 'OPEN', depotCode: 'PEL', outletId: 'OUT001',
  subjects: [{ type: 'order', id: ORDER }], resolutionAction: null, raisedAt: '2026-10-03T01:00:00Z', resolvedAt: null, rowVersion: 2,
  description: 'Driver Sunil left it at the back door', assignee: USER, resolutionNote: null, raisedBy: USER };

async function body(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : undefined;
}

async function fixture(context: Context) {
  const seen: Seen[] = [];
  const http = createServer(async (req, res) => {
    const payload = await body(req);
    seen.push({ method: req.method ?? '', path: req.url ?? '', body: payload });
    res.setHeader('content-type', 'application/json');
    if (req.url === '/api/mcp/context') {
      res.end(JSON.stringify({ userId: USER, roles: ['store_manager'], scope: ['outlet:OUT001'], readActions: context.readActions ?? ['issue:Read'],
        grantedScopes: context.grantedScopes ?? ['waypoint.read', 'issues.write'], writeTools: context.writeTools ?? ['assign_issue', 'raise_issue'],
        personalFields: context.personalFields ?? false }));
    } else if (req.url === '/api/mcp/writes') {
      const request = payload as { tool: string; expectedVersion: number | null; payload: Record<string, unknown> };
      res.end(JSON.stringify({ confirmation: 'mcpw.confirmation-token-abcdef', expiresAt: '2026-10-03T01:02:00Z', tool: request.tool,
        kind: request.tool === 'raise_issue' ? 'issue:Raise' : 'issue:Assign', commandId: COMMAND,
        expectedVersion: request.expectedVersion, payload: request.payload }));
    } else if (req.url === '/api/mcp/writes/confirm') {
      res.end(JSON.stringify({ commandId: COMMAND, tool: 'raise_issue', kind: 'issue:Raise', replayed: false,
        result: { issueId: ISSUE, status: 'OPEN', rowVersion: 1, secret: 'never-return' } }));
    } else if (req.url === `/api/issues/${ISSUE}`) {
      res.end(JSON.stringify(issue));
    } else {
      res.writeHead(404, { 'content-type': 'application/problem+json' });
      res.end(JSON.stringify({ code: 'NOT_FOUND', violations: [] }));
    }
  });
  await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
  const address = http.address();
  assert.ok(address && typeof address !== 'string');
  const server = createMcpServer(new BackendClient(`http://127.0.0.1:${address.port}`, 'mcp.test'), () => {});
  const client = new Client({ name: 'waypoint-writes-test', version: '1.0.0' });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  return { client, seen, origin: `http://127.0.0.1:${address.port}`, close: async () => {
    await client.close();
    await server.close();
    http.closeAllConnections();
    await new Promise<void>(resolve => http.close(() => resolve()));
  } };
}

const raise = { type: 'DAMAGED_GOODS', severity: 'LOW', depotCode: 'PEL', outletId: 'OUT001',
  subjects: [{ type: 'order', id: ORDER }], description: 'broken pallet' };

test('a write tool only prepares; confirm_write submits nothing but the confirmation', async () => {
  const f = await fixture({});
  try {
    const { tools } = await f.client.listTools();
    const byName = new Map(tools.map(t => [t.name, t]));
    assert.ok(byName.has('raise_issue') && byName.has('assign_issue') && byName.has('confirm_write'));
    assert.equal(byName.get('confirm_write')!.annotations?.readOnlyHint, false, 'clients should ask the person before confirming');
    assert.equal(byName.get('confirm_write')!.annotations?.destructiveHint, true);
    assert.match(byName.get('raise_issue')!.description!, /Nothing changes yet/);

    const prepared = await f.client.callTool({ name: 'raise_issue', arguments: raise });
    assert.equal(prepared.isError, undefined);
    const preview = (prepared.structuredContent as { data: { confirmation: string; kind: string } }).data;
    assert.equal(preview.kind, 'issue:Raise');
    assert.deepEqual(f.seen.filter(s => s.method === 'POST').map(s => s.path), ['/api/mcp/writes'], 'preparing posts only the prepare');
    assert.deepEqual((f.seen.at(-1)!.body as { expectedVersion: unknown }).expectedVersion, null);

    const confirmed = await f.client.callTool({ name: 'confirm_write', arguments: { confirmation: preview.confirmation } });
    assert.equal(confirmed.isError, undefined);
    assert.deepEqual(f.seen.at(-1)!.body, { confirmation: preview.confirmation }, 'confirm sends the confirmation, never a command');
    assert.ok(!JSON.stringify(confirmed).includes('never-return'), 'only known result fields reach the assistant');
    assert.ok(!f.seen.some(s => s.path === '/api/commands'), 'the adapter never reaches the command endpoint');

    const assign = await f.client.callTool({ name: 'assign_issue', arguments: { issueId: ISSUE, assigneeUserId: USER, expectedVersion: 2 } });
    assert.equal(assign.isError, undefined);
    assert.deepEqual(f.seen.at(-1)!.body, { tool: 'assign_issue', expectedVersion: 2, payload: { issueId: ISSUE, assigneeUserId: USER } });

    for (const bad of [{ ...raise, subjects: [] }, { ...raise, type: 'MADE_UP' }, { ...raise, extra: 'field' }]) {
      const refused = await f.client.callTool({ name: 'raise_issue', arguments: bad });
      assert.equal(refused.isError, true);
    }
    const forged = await f.client.callTool({ name: 'confirm_write', arguments: { confirmation: 'not-a-confirmation' } });
    assert.equal(forged.isError, true);
  } finally { await f.close(); }
});

test('without the write scope a write names the scope it needs; without the grant there are no write tools', async () => {
  const narrow = await fixture({ grantedScopes: ['waypoint.read'], writeTools: [] });
  try {
    assert.ok(!(await narrow.client.listTools()).tools.some(t => t.name === 'raise_issue' || t.name === 'confirm_write'));
    const refused = await narrow.client.callTool({ name: 'raise_issue', arguments: raise });
    const error = JSON.parse((refused.content as { text: string }[])[0]!.text);
    assert.deepEqual([error.code, error.status, error.requiredScope], ['INSUFFICIENT_SCOPE', 403, 'issues.write']);
    assert.ok(!narrow.seen.some(s => s.method === 'POST'), 'nothing is prepared');
  } finally { await narrow.close(); }
  const off = await fixture({ writeTools: [] });
  try {
    const refused = await off.client.callTool({ name: 'raise_issue', arguments: raise });
    assert.equal(JSON.parse((refused.content as { text: string }[])[0]!.text).code, 'FORBIDDEN');
    const confirm = await off.client.callTool({ name: 'confirm_write', arguments: { confirmation: 'mcpw.confirmation-token-abcdef' } });
    assert.equal(confirm.isError, true);
    assert.ok(!off.seen.some(s => s.method === 'POST'));
  } finally { await off.close(); }
});

test('personal fields are withheld and said to be, unless the person holds the grant', async () => {
  const without = await fixture({});
  try {
    const read = await without.client.callTool({ name: 'get_issue', arguments: { issueId: ISSUE } });
    const text = JSON.stringify(read);
    assert.ok(!text.includes('Sunil') && !text.includes('raisedBy'), text);
    assert.deepEqual((read.structuredContent as { withheld: string[] }).withheld, ['personal']);
  } finally { await without.close(); }
  const granted = await fixture({ personalFields: true });
  try {
    const read = await granted.client.callTool({ name: 'get_issue', arguments: { issueId: ISSUE } });
    const data = (read.structuredContent as { data: { description: string; raisedBy: string }; withheld?: string[] });
    assert.equal(data.data.description, issue.description);
    assert.equal(data.data.raisedBy, USER);
    assert.equal(data.withheld, undefined);
  } finally { await granted.close(); }
});

test('field classes name each personal field once and withholding reaches nested records', () => {
  assert.equal(new Set(fieldClasses.personal).size, fieldClasses.personal.length);
  assert.ok(!fieldClasses.personal.some(f => (fieldClasses.internal as readonly string[]).includes(f)));
  const { value, withheld } = withholdPersonal({ items: [{ issueId: ISSUE, note: 'x', receipt: { confirmedBy: USER, status: 'OK' } }] });
  assert.ok(withheld);
  assert.deepEqual(value, { items: [{ issueId: ISSUE, receipt: { status: 'OK' } }] });
  assert.deepEqual(withholdPersonal({ issueId: ISSUE, assignee: null }), { value: { issueId: ISSUE }, withheld: false }, 'an empty field is not a withholding');
});

test('the remote transport answers a write without its scope with an insufficient_scope challenge', async () => {
  assert.equal(missingScope({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'raise_issue' } }, ['waypoint.read']), 'issues.write');
  assert.equal(missingScope([{ method: 'tools/call', params: { name: 'get_issue' } }], ['orders.read']), null, 'reads are narrowed by the backend');
  assert.equal(missingScope({ method: 'tools/call', params: { name: 'raise_issue' } }, ['issues.write']), null);

  const f = await fixture({ grantedScopes: ['waypoint.read'], writeTools: [] });
  const front = createServer();
  await new Promise<void>(resolve => front.listen(0, '127.0.0.1', resolve));
  const address = front.address();
  assert.ok(address && typeof address !== 'string');
  const origin = `http://127.0.0.1:${address.port}`;
  front.on('request', createHttpHandler({ backendUrl: f.origin, publicUrl: origin + '/mcp', enabled: true }));
  try {
    const response = await fetch(origin + '/mcp', { method: 'POST',
      headers: { Authorization: 'Bearer mcp.test', 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'raise_issue', arguments: raise } }) });
    assert.equal(response.status, 403);
    const header = response.headers.get('www-authenticate') ?? '';
    assert.match(header, /error="insufficient_scope"/);
    assert.match(header, /scope="waypoint\.read issues\.write"/);
    assert.match(header, /resource_metadata=/);
    assert.ok(!f.seen.some(s => s.method === 'POST'));
  } finally {
    front.closeAllConnections();
    await new Promise<void>(resolve => front.close(() => resolve()));
    await f.close();
  }
});
