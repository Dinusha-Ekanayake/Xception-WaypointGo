// Opt-in smoke check against a disposable environment and a dedicated test account.
import assert from 'node:assert/strict';
import { auth } from '@modelcontextprotocol/sdk/client/auth.js';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
const endpoint = new URL(process.env.MCP_SMOKE_URL ?? 'http://127.0.0.1:43220/mcp');
const email = process.env.MCP_SMOKE_EMAIL, password = process.env.MCP_SMOKE_PASSWORD;
assert.ok(email && password, 'Set dedicated MCP_SMOKE_EMAIL and MCP_SMOKE_PASSWORD');
let info, tokens, verifier, authorization;
const redirect = 'http://127.0.0.1:49151/callback';
const provider = {
  redirectUrl: redirect,
  clientMetadata: { client_name: 'Waypoint protocol smoke', redirect_uris: [redirect], grant_types: ['authorization_code'], response_types: ['code'], token_endpoint_auth_method: 'none', scope: 'waypoint.read' },
  clientInformation: () => info, saveClientInformation: x => { info = x; },
  tokens: () => tokens, saveTokens: x => { tokens = x; },
  codeVerifier: () => verifier, saveCodeVerifier: x => { verifier = x; },
  redirectToAuthorization: x => { authorization = x; }, state: () => 'smoke-state',
};
assert.equal(await auth(provider, { serverUrl: endpoint }), 'REDIRECT');
assert.equal(authorization.origin, endpoint.origin);
const described = await fetch(new URL('/api/oauth/authorize' + authorization.search, endpoint));
assert.equal(described.status, 200);
const body = { email, password };
for (const [camel, snake] of Object.entries({ clientId: 'client_id', redirectUri: 'redirect_uri', responseType: 'response_type', codeChallenge: 'code_challenge', codeChallengeMethod: 'code_challenge_method', state: 'state', resource: 'resource', scope: 'scope' })) body[camel] = authorization.searchParams.get(snake);
const approval = await fetch(new URL('/api/oauth/authorize', endpoint), { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: endpoint.origin }, body: JSON.stringify(body) });
assert.equal(approval.status, 200);
const callback = new URL((await approval.json()).redirectTo);
assert.equal(callback.searchParams.get('state'), 'smoke-state');
assert.equal(await auth(provider, { serverUrl: endpoint, authorizationCode: callback.searchParams.get('code') }), 'AUTHORIZED');
const client = new Client({ name: 'protocol-smoke', version: '1' });
try {
  await client.connect(new StreamableHTTPClientTransport(endpoint, { requestInit: { headers: { Authorization: `Bearer ${tokens.access_token}` } } }));
  const list = await client.listTools(); assert.ok(list.tools.some(t => t.name === 'my_context'));
  const context = await client.callTool({ name: 'my_context', arguments: {} }); assert.ok(!context.isError);
  const revoke = await fetch(new URL('/api/oauth/revoke', endpoint), { method: 'POST', body: new URLSearchParams({ token: tokens.access_token, client_id: info.client_id }) });
  assert.equal(revoke.status, 200);
  await assert.rejects(client.listTools());
  console.log('PASS: SDK discovery, DCR, PKCE consent/exchange, scoped context, revocation through public routes');
} finally { await client.close(); }
