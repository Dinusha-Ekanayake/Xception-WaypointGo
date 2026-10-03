import type { IncomingMessage, ServerResponse } from 'node:http';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { BackendClient, BackendError, backendOrigin } from './client.ts';
import { createMcpServer } from './server.ts';

export function createHttpHandler(config: { backendUrl: string; publicUrl: string; enabled: boolean }) {
  const publicUrl = config.publicUrl ? new URL(config.publicUrl) : null;
  if (publicUrl && (publicUrl.pathname !== '/mcp' || publicUrl.search || publicUrl.hash || publicUrl.username || publicUrl.password || publicUrl.href !== config.publicUrl)) throw new Error('MCP_PUBLIC_URL must end in /mcp');
  if (publicUrl) backendOrigin(publicUrl.origin);
  backendOrigin(config.backendUrl, true);
  return async (req: IncomingMessage, res: ServerResponse) => {
    const fail = (status: number, code: string) => {
      res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ error: code }));
    };
    if (req.url === '/health' && req.method === 'GET') { res.end('UP'); return; }
    if (!config.enabled || !publicUrl || req.url !== '/mcp') { fail(404, 'not_found'); return; }
    res.setHeader('Cache-Control', 'no-store');
    if (req.headers.origin && req.headers.origin !== publicUrl.origin) { fail(403, 'invalid_origin'); return; }
    if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); fail(405, 'method_not_allowed'); return; }
    const challenge = () => {
      res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${publicUrl.origin}/.well-known/oauth-protected-resource/mcp", scope="waypoint.read"`);
      fail(401, 'invalid_token');
    };
    const token = /^Bearer (mcp\.[A-Za-z0-9_-]+)$/.exec(req.headers.authorization ?? '')?.[1];
    if (!token) { challenge(); return; }
    try {
      const backend = new BackendClient(config.backendUrl, token, { resource: config.publicUrl, allowInternalHttp: true });
      await backend.get('/api/mcp/context');
      if (!req.headers['content-type']?.toLowerCase().startsWith('application/json')) { fail(415, 'unsupported_media_type'); return; }
      const chunks: Buffer[] = []; let size = 0;
      for await (const chunk of req.iterator({ destroyOnReturn: false })) {
        size += chunk.length;
        if (size > 65536) { req.resume(); fail(413, 'request_too_large'); return; }
        chunks.push(Buffer.from(chunk));
      }
      let body: unknown;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { fail(400, 'invalid_json'); return; }
      const server = createMcpServer(backend);
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      res.once('close', () => { void server.close(); });
      await server.connect(transport);
      await transport.handleRequest(req, res, body);
    } catch (error) {
      if (res.headersSent) { res.end(); return; }
      if (error instanceof BackendError && error.status === 401) { challenge(); return; }
      fail(error instanceof BackendError && error.status === 403 ? 403 : 503, 'access_unavailable');
    }
  };
}
