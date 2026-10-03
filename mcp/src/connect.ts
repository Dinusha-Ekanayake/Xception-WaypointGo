import { access, rm } from 'node:fs/promises';
import { createInterface } from 'node:readline/promises';
import { Writable } from 'node:stream';
import { z } from 'zod';
import { backendOrigin, readJson } from './client.ts';
import { credentialPath, readConnection, saveConnection } from './credentials.ts';

async function revoke(backendUrl: string, token: string): Promise<void> {
  const response = await fetch(`${backendUrl}/api/mcp/session/end`, {
    method: 'POST', redirect: 'error', headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok && response.status !== 401) throw new Error('Revocation failed');
}

async function main() {
  const file = credentialPath();
  if (process.argv.includes('--disconnect')) {
    const connection = await readConnection(file);
    await revoke(connection.backendUrl, connection.token);
    await rm(file);
    process.stderr.write('Waypoint MCP connection revoked and local credential removed.\n');
    return;
  }
  if (!process.stdin.isTTY) throw new Error('Use a trusted interactive terminal');
  // Do not mint an inaccessible credential when an existing file would block saving it.
  try { await access(file); throw new Error('Disconnect the existing connection first'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  let muted = false;
  const output = new Writable({ write(chunk, _encoding, callback) {
    if (!muted) process.stderr.write(chunk);
    callback();
  } });
  const input = createInterface({ input: process.stdin, output, terminal: true });
  try {
    const backendUrl = backendOrigin(await input.question('Waypoint URL (HTTPS, or loopback HTTP): '));
    const email = await input.question('Email: ');
    process.stderr.write('Password: ');
    muted = true;
    const password = await input.question('');
    muted = false;
    process.stderr.write('\n');
    const response = await fetch(`${backendUrl}/api/mcp/session`, {
      method: 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }), signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error('Connection refused');
    }
    const { token } = z.object({ token: z.string().regex(/^mcp\.[A-Za-z0-9_-]+$/) }).parse(await readJson(response));
    try { await saveConnection(file, { backendUrl, token }); }
    catch (error) { await revoke(backendUrl, token); throw error; }
    process.stderr.write('Waypoint read-only MCP connected. Credential saved privately; no token is printed.\n');
  } finally { input.close(); output.end(); }
}

main().catch(() => {
  process.stderr.write('Connection setup failed. Check MCP_ENABLED, your account, URL and private credential file. Disconnect an existing connection before replacing it.\n');
  process.exitCode = 1;
});
