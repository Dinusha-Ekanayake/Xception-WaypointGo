import { spawn } from "node:child_process";
import { createTestDatabase } from "../tests/helpers/database.ts";
import { databaseProxy } from "../tests/helpers/database-proxy.ts";
import { Service } from "../lib/service.ts";

const fixture = await createTestDatabase();
const proxy = await databaseProxy(fixture.url);
process.env.DEMO_MODE = "1";
process.env.SEED_PASSWORD = "Waypoint2026!";
let child: ReturnType<typeof spawn> | undefined;
const stop = () => child?.kill("SIGTERM");
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
try {
  await new Service(fixture.db).seed();
  child = spawn("node", ["node_modules/@playwright/test/cli.js", "test", ...process.argv.slice(2)], {
    stdio: "inherit",
    env: { ...process.env, DATABASE_URL: proxy?.url || fixture.url, TEST_DB_PROXY_CONTROL: proxy?.control || "", TEST_DB_PROXY_TOKEN: proxy?.token || "", DATABASE_SCHEMA: fixture.schema, COOKIE_SECURE: "0" },
  });
  process.exitCode = await new Promise<number>((resolve, reject) => {
    child!.on("error", reject);
    child!.on("exit", (code) => resolve(code ?? 1));
  });
} finally {
  process.off("SIGINT", stop);
  process.off("SIGTERM", stop);
  await proxy?.close();
  await fixture.close();
}
