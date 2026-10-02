import { createServer } from 'node:http';
import { createHttpHandler } from './http.ts';
const port = Number(process.env.MCP_PORT ?? '8081');
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid MCP_PORT');
const server = createServer({ requestTimeout: 15000, headersTimeout: 10000 }, createHttpHandler({
  backendUrl: process.env.MCP_BACKEND_URL ?? 'http://127.0.0.1:8080',
  publicUrl: process.env.MCP_PUBLIC_URL ?? '', enabled: process.env.MCP_ENABLED === 'true' || process.env.MCP_ENABLED === '1',
}));
server.listen(port, '0.0.0.0');
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
