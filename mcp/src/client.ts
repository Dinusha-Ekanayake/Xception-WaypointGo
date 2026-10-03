import { randomUUID } from 'node:crypto';
import { z } from 'zod';

export const MAX_RESPONSE_BYTES = 256 * 1024;
const problem = z.object({ code: z.string().max(80), correlationId: z.string().max(100).optional(),
  violations: z.array(z.object({ rule: z.string().max(100), field: z.string().max(100).optional() })).max(50).optional() });

export class BackendError extends Error {
  readonly code: string;
  readonly status: number;
  readonly correlationId: string;
  readonly violations: { rule: string; field?: string }[];
  /** From the backend's Retry-After on a 429 (R-IAM-33), so the assistant knows when to try again. */
  readonly retryAfterSeconds: number | undefined;
  constructor(code: string, status: number, correlationId = '',
    violations: { rule: string; field?: string }[] = [], retryAfterSeconds?: number) {
    super(code);
    this.code = code;
    this.status = status;
    this.correlationId = correlationId;
    this.violations = violations;
    this.retryAfterSeconds = retryAfterSeconds;
  }
  toJSON() {
    return { code: this.code, status: this.status, correlationId: this.correlationId, violations: this.violations,
      ...(this.retryAfterSeconds === undefined ? {} : { retryAfterSeconds: this.retryAfterSeconds }) };
  }
}

export function backendOrigin(value: string, allowInternalHttp = false): string {
  const url = new URL(value);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/'
    || (url.protocol !== 'https:' && !(url.protocol === 'http:' && (loopback || allowInternalHttp)))) {
    throw new Error('Backend URL must be an HTTPS origin, or loopback HTTP for local development');
  }
  return url.origin;
}

export async function readJson(response: Response): Promise<unknown> {
  const declared = Number(response.headers.get('content-length'));
  if (declared > MAX_RESPONSE_BYTES) {
    await response.body?.cancel();
    throw new BackendError('RESPONSE_TOO_LARGE', 413);
  }
  const reader = response.body?.getReader();
  if (!reader) throw new BackendError('INVALID_BACKEND_RESPONSE', 502);
  let bytes = 0;
  const chunks: Uint8Array[] = [];
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new BackendError('RESPONSE_TOO_LARGE', 413);
      }
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown;
  } catch (error) {
    if (error instanceof BackendError) throw error;
    throw new BackendError('INVALID_BACKEND_RESPONSE', 502);
  } finally { reader.releaseLock(); }
}

export class BackendClient {
  private readonly origin: string;
  private readonly token: string;
  private readonly options: { resource?: string; allowInternalHttp?: boolean };
  constructor(origin: string, token: string, options: { resource?: string; allowInternalHttp?: boolean } = {}) {
    this.options = options;
    this.origin = backendOrigin(origin, options.allowInternalHttp);
    this.token = token;
    if (!/^mcp\.[A-Za-z0-9_-]+$/.test(token)) throw new Error('A dedicated MCP credential is required');
  }

  async get(path: string): Promise<unknown> {
    const url = new URL(path, this.origin);
    if (url.origin !== this.origin || !path.startsWith('/api/') || path.includes('#')) {
      throw new BackendError('INVALID_READ_PATH', 400);
    }
    let response: Response;
    try {
      response = await fetch(url, { method: 'GET', redirect: 'error', cache: 'no-store',
        headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json', ...(this.options.resource ? { 'X-Waypoint-Mcp-Resource': this.options.resource } : {}), 'X-Correlation-Id': randomUUID() },
        signal: AbortSignal.timeout(10_000) });
    } catch {
      throw new BackendError('DEPENDENCY_UNAVAILABLE', 503);
    }
    const data = await readJson(response);
    if (!response.ok) {
      const parsed = problem.safeParse(data);
      const retry = Number(response.headers.get('retry-after'));
      const retryAfter = Number.isInteger(retry) && retry > 0 && retry <= 3600 ? retry : undefined;
      throw parsed.success ? new BackendError(parsed.data.code, response.status, parsed.data.correlationId, parsed.data.violations, retryAfter)
        : new BackendError('DEPENDENCY_UNAVAILABLE', response.status, '', [], retryAfter);
    }
    return data;
  }
}
