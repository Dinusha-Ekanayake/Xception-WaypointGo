import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createTestDatabase } from "./database.ts";
import { databaseProxy } from "./database-proxy.ts";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
export async function startSpring(port = 43220, demo = true) {
  const fixture = await createTestDatabase(false);
  const proxy = await databaseProxy(fixture.url);
  const env = {
    ...process.env, DATABASE_URL: proxy?.url || fixture.url, DATABASE_SCHEMA: fixture.schema,
    DATA_DIR: path.join(root, "data"), MIGRATIONS_DIR: path.join(root, "migrations"),
    DEMO_MODE: demo ? "1" : "0", SEED_PASSWORD: "Waypoint2026!", COOKIE_SECURE: "0",
    DEMO_NOW: "2026-02-13T15:30:00+05:30",
  };
  const jar = path.join(root, "backend/target/waypoint-dispatch-backend-1.0.0.jar");
  let server: ChildProcess | undefined;
  let output = "";
  const launch = (args: string[], overrides: Record<string, string> = {}) => {
    const child = spawn("java", ["-jar", jar, ...args, "--server.port=" + port], { cwd: root, env: { ...env, ...overrides }, stdio: ["ignore", "pipe", "pipe"] });
    child.stdout!.on("data", d => { output = (output + d).slice(-20000); });
    child.stderr!.on("data", d => { output = (output + d).slice(-20000); });
    return child;
  };
  const close = async () => {
    if (server && server.exitCode === null) {
      const exited = once(server, "exit"); server.kill("SIGTERM"); await exited;
    }
    await proxy?.close(); await fixture.close();
  };
  try {
    for (const command of ["migrate", ...(demo ? ["seed"] : [])]) {
      const child = launch([command]);
      const [code] = await once(child, "exit");
      if (code !== 0) throw new Error(command + " failed:\n" + output);
    }
    server = launch([]);
    const url = `http://127.0.0.1:${port}`;
    const deadline = Date.now() + 60000;
    while (true) {
      if (server.exitCode !== null || Date.now() > deadline) throw new Error("Spring failed to start:\n" + output);
      try { if ((await fetch(url + "/api/health")).ok) break; } catch { /* startup */ }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    const cli = async (command: string, overrides: Record<string, string>) => {
      const child = launch([command], overrides);
      const [code] = await once(child, "exit");
      return code;
    };
    return { ...fixture, url, env, proxy, close, cli };
  } catch (error) { await close(); throw error; }
}
