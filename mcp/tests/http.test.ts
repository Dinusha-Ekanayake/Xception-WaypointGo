import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type Server } from 'node:http';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { createHttpHandler } from '../src/http.ts';

async function listen(server: Server) {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address(); assert.ok(address && typeof address !== 'string');
  return `http://127.0.0.1:${address.port}`;
}
async function close(server: Server) { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())); }
async function fixture() {
  let revoked = false;
  const calls: { path: string; token: string | undefined; resource: string | string[] | undefined }[] = [];
  const upstream = createServer((req, res) => {
    calls.push({ path: req.url!, token: req.headers.authorization, resource: req.headers['x-waypoint-mcp-resource'] });
    res.setHeader('content-type', 'application/json');
    if (revoked || !['Bearer mcp.alice', 'Bearer mcp.bob'].includes(req.headers.authorization ?? '')) {
      res.writeHead(401); res.end(JSON.stringify({ code: 'UNAUTHENTICATED' })); return;
    }
    const alice = req.headers.authorization === 'Bearer mcp.alice';
    res.end(JSON.stringify({ userId: alice ? '11111111-1111-4111-8111-111111111111' : '22222222-2222-4222-8222-222222222222',
      roles: [alice ? 'store_manager' : 'auditor'], scope: [alice ? 'outlet:OUT001' : 'audit'], readActions: [alice ? 'order:Read' : 'audit:Read'] }));
  });
  const backendUrl = await listen(upstream);
  const server = createServer(); const origin = await listen(server);
  server.on('request', createHttpHandler({ backendUrl, publicUrl: origin + '/mcp', enabled: true }));
  return { server, origin, calls, revoke: () => { revoked = true; }, close: async () => { await close(server); await close(upstream); } };
}

test('HTTP challenge, origin validation, methods and body limits fail closed', async () => {
  const f = await fixture();
  try {
    const missing = await fetch(f.origin + '/mcp', { method: 'POST' });
    assert.equal(missing.status, 401);
    assert.ok(missing.headers.get('www-authenticate')?.includes('/.well-known/oauth-protected-resource'));
    for (const method of ['GET', 'DELETE']) assert.equal((await fetch(f.origin + '/mcp', { method })).status, 405);
    const crossSite = await fetch(f.origin + '/mcp', { method: 'POST', headers: { Origin: 'https://attacker.test', Authorization: 'Bearer mcp.alice' } });
    assert.equal(crossSite.status, 403);
    const huge = await fetch(f.origin + '/mcp', { method: 'POST', headers: { Authorization: 'Bearer mcp.alice', 'Content-Type': 'application/json' }, body: ' '.repeat(70_000) });
    assert.equal(huge.status, 413);
    assert.ok(f.calls.every(c => c.path === '/api/mcp/context'));
  } finally { await f.close(); }
});

test('official HTTP SDK isolates users, preserves read-only tools and rechecks revocation', async () => {
  const f = await fixture();
  const a = new Client({ name: 'alice', version: '1' }), b = new Client({ name: 'bob', version: '1' });
  try {
    await a.connect(new StreamableHTTPClientTransport(new URL(f.origin + '/mcp'), { requestInit: { headers: { Authorization: 'Bearer mcp.alice' } } }));
    await b.connect(new StreamableHTTPClientTransport(new URL(f.origin + '/mcp'), { requestInit: { headers: { Authorization: 'Bearer mcp.bob' } } }));
    const [at, bt] = await Promise.all([a.listTools(), b.listTools()]);
    assert.ok(at.tools.some(t => t.name === 'get_order'));
    assert.ok(!at.tools.some(t => t.name === 'list_audit'));
    assert.ok(bt.tools.some(t => t.name === 'list_audit'));
    const result = await a.callTool({ name: 'my_context', arguments: {} });
    assert.ok(JSON.stringify(result).includes('OUT001'));
    assert.ok(!JSON.stringify(result).includes('mcp.alice'));
    assert.ok(f.calls.every(c => c.resource === f.origin + '/mcp'));
    const write = await a.callTool({ name: 'publish_plan', arguments: {} }); assert.equal(write.isError, true);
    f.revoke(); await assert.rejects(a.listTools());
  } finally { await a.close(); await b.close(); await f.close(); }
});

test('disabled remote transport exposes no protocol and invalid public URLs fail startup', async () => {
  const server = createServer(createHttpHandler({ backendUrl: 'http://127.0.0.1:1', publicUrl: '', enabled: false }));
  const origin = await listen(server);
  try {
    assert.equal((await fetch(origin + '/mcp', { method: 'POST' })).status, 404);
    assert.equal((await fetch(origin + '/health')).status, 200);
  } finally { await close(server); }
  for (const publicUrl of ['https://user:secret@waypoint.test/mcp', 'https://waypoint.test/mcp?token=x', 'http://waypoint.test/mcp']) {
    assert.throws(() => createHttpHandler({ backendUrl: 'http://backend:8080', publicUrl, enabled: true }));
  }
});
