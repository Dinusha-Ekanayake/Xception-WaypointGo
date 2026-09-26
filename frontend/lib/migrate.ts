import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { Database } from "./database.ts";

export async function migrate(db: Database): Promise<void> {
  // Dedicated connection, READ COMMITTED: wait for competing migrations before reading their results.
  const client = await db.pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock(71842001)");
    await client.query("CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, checksum text NOT NULL)");
    const directory = existsSync(path.join(process.cwd(), "migrations"))
      ? path.join(process.cwd(), "migrations")
      : path.join(process.cwd(), "..", "migrations");
    for (const name of (await readdir(directory)).filter((n) => n.endsWith(".sql")).sort()) {
      const sql = await readFile(path.join(directory, name), "utf8");
      const checksum = createHash("sha256").update(sql).digest("hex");
      const existing = (await client.query("SELECT checksum FROM schema_migrations WHERE name=$1", [name])).rows[0];
      if (existing) {
        if (existing.checksum !== checksum) throw new Error(`Applied migration changed: ${name}`);
        continue;
      }
      await client.query(sql);
      await client.query("INSERT INTO schema_migrations VALUES ($1,$2)", [name, checksum]);
    }
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally { client.release(); }
}
