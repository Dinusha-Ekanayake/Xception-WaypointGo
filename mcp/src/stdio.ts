import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { BackendClient } from './client.ts';
import { credentialPath, readConnection } from './credentials.ts';
import { createMcpServer } from './server.ts';

async function main() {
  const connection = await readConnection(credentialPath());
  const server = createMcpServer(new BackendClient(connection.backendUrl, connection.token));
  await server.connect(new StdioServerTransport());
  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => { void server.close().finally(() => process.exit(0)); });
  }
}

main().catch(() => {
  // stdout belongs exclusively to MCP. Never print file contents or thrown values.
  process.stderr.write('Waypoint MCP could not start. Check the private credential file and run connect again if expired.\n');
  process.exitCode = 1;
});
