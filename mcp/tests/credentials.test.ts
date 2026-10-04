import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, chmod, lstat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readConnection, saveConnection } from '../src/credentials.ts';
import { backendOrigin } from '../src/client.ts';

test('credential files are private and never overwrite an existing connection', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'waypoint-credential-test-'));
  const path = join(dir, 'connection.json');
  try {
    await saveConnection(path, { backendUrl: 'http://127.0.0.1:8080', token: 'mcp.test' });
    assert.equal((await lstat(path)).mode & 0o777, 0o600);
    assert.equal((await readConnection(path)).token, 'mcp.test');
    await assert.rejects(saveConnection(path, { backendUrl: 'http://127.0.0.1:8080', token: 'mcp.other' }));
    await chmod(path, 0o644);
    await assert.rejects(readConnection(path), /private/);
  } finally { await rm(dir, { recursive: true }); }
});

test('trusted origin configuration rejects non-loopback plain HTTP, credentials and URL paths', () => {
  for (const value of ['http://example.test', 'https://user:secret@example.test', 'https://example.test/api', 'https://example.test/#secret']) {
    assert.throws(() => backendOrigin(value));
  }
  assert.equal(backendOrigin('https://example.test'), 'https://example.test');
  assert.equal(backendOrigin('http://127.0.0.1:8080'), 'http://127.0.0.1:8080');
});
