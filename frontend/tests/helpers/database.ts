import { randomBytes } from "node:crypto";
import type { TestContext } from "node:test";
import { Database } from "../../lib/database.ts";
import { migrate } from "../../lib/migrate.ts";

export async function createTestDatabase(migrateSchema = true) {
  const url = process.env.TEST_DATABASE_URL;
  if (!url) throw new Error("Set TEST_DATABASE_URL to a dedicated disposable PostgreSQL database. Application DATABASE_URL is never used for tests.");
  if (url === process.env.DATABASE_URL) throw new Error("TEST_DATABASE_URL must differ from application DATABASE_URL.");
  const schema = `waypoint_test_${randomBytes(10).toString("hex")}`;
  const admin = new Database(url);
  await admin.run(`CREATE SCHEMA ${schema}`);
  const db = new Database(url, schema);
  const close = async () => {
    await db.close();
    await admin.run(`DROP SCHEMA ${schema} CASCADE`);
    await admin.close();
  };
  try { if (migrateSchema) await migrate(db); } catch (error) { await close(); throw error; }
  return { db, schema, url, close };
}

export async function testDatabase(t: TestContext): Promise<Database> {
  process.env.DEMO_MODE = "1";
  process.env.SEED_PASSWORD = "Waypoint2026!";
  const fixture = await createTestDatabase();
  t.after(fixture.close);
  return fixture.db;
}
