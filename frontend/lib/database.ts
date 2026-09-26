import { AsyncLocalStorage } from "node:async_hooks";
import pg from "pg";
import { attachDatabasePool } from "@vercel/functions";

export type DbRow = Record<string, string | number | null | Buffer>;

export class Database {
  readonly pool: pg.Pool;
  private readonly context = new AsyncLocalStorage<pg.PoolClient>();

  constructor(connectionString: string, schema = "public") {
    if (!connectionString) throw new Error("DATABASE_URL is required. Run db:migrate and db:seed before starting.");
    if (!/^[a-z][a-z0-9_]*$/.test(schema)) throw new Error("Invalid database schema.");
    this.pool = new pg.Pool({
      connectionString,
      max: 3,
      connectionTimeoutMillis: 10000,
      idleTimeoutMillis: 10000,
      ...(schema !== "public" ? { options: `-c search_path=${schema}` } : {}),
      query_timeout: 15000,
      // Keep JSON storage decoding explicit at the service boundary.
      types: { getTypeParser: (oid, format) => oid === 3802 || oid === 114 ? (value: string) => value : pg.types.getTypeParser(oid, format) },
    });
    this.pool.on("error", (error) => console.error("Idle database connection failed:", error.message));
    if (process.env.VERCEL) attachDatabasePool(this.pool);
  }

  async all(sql: string, ...params: unknown[]): Promise<DbRow[]> {
    const client = this.context.getStore() || this.pool;
    return (await client.query(sql, params)).rows as DbRow[];
  }

  async get(sql: string, ...params: unknown[]): Promise<DbRow | undefined> {
    return (await this.all(sql, ...params))[0];
  }

  async run(sql: string, ...params: unknown[]): Promise<void> {
    await this.all(sql, ...params);
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    if (this.context.getStore()) return fn();
    for (let attempt = 0; ; attempt++) {
      const client = await this.pool.connect();
      let retry = false;
      try {
        await client.query("BEGIN ISOLATION LEVEL SERIALIZABLE");
        await client.query("SET LOCAL statement_timeout = '15s'");
        await client.query("SET LOCAL idle_in_transaction_session_timeout = '15s'");
        const result = await this.context.run(client, fn);
        await client.query("COMMIT");
        return result;
      } catch (error) {
        await client.query("ROLLBACK").catch(() => {});
        const { code, constraint } = error as { code?: string; constraint?: string };
        retry = attempt < 4 && (code === "40001" || code === "40P01" || (code === "23505" && constraint === "commands_pkey"));
        if (!retry) throw error;
      } finally {
        client.release();
      }
      if (retry) await new Promise((resolve) => setTimeout(resolve, 20 * (attempt + 1)));
    }
  }

  async close(): Promise<void> { await this.pool.end(); }
}
