import { constants } from 'node:fs';
import { mkdir, open } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { z } from 'zod';
import { backendOrigin } from './client.ts';

const connection = z.strictObject({ backendUrl: z.string(), token: z.string().regex(/^mcp\.[A-Za-z0-9_-]+$/) });
export type Connection = z.infer<typeof connection>;
export function credentialPath(): string {
  return process.env.WAYPOINT_MCP_CREDENTIAL_FILE ?? join(homedir(), '.config', 'waypoint', 'mcp.json');
}

export async function readConnection(path: string): Promise<Connection> {
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > 8192 || (process.platform !== 'win32' && ((stat.mode & 0o077) !== 0
      || (process.getuid && stat.uid !== process.getuid())))) {
      throw new Error('Credential file must be a small private file owned by you');
    }
    const value = connection.parse(JSON.parse(await file.readFile('utf8')));
    return { backendUrl: backendOrigin(value.backendUrl), token: value.token };
  } finally { await file.close(); }
}

export async function saveConnection(path: string, value: Connection): Promise<void> {
  const safe = connection.parse(value);
  safe.backendUrl = backendOrigin(safe.backendUrl);
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const file = await open(path, 'wx', 0o600);
  try { await file.writeFile(JSON.stringify(safe) + '\n'); }
  finally { await file.close(); }
}
