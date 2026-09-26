import { spawn } from "node:child_process";
import { once } from "node:events";
import { startSpring } from "../tests/helpers/spring.ts";

const backend = await startSpring();
let child: ReturnType<typeof spawn> | undefined;
const stop = () => child?.kill("SIGTERM");
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
try {
  child = spawn("node", ["node_modules/@playwright/test/cli.js", "test", ...process.argv.slice(2)], {
    stdio: "inherit",
    env: { ...backend.env, BACKEND_URL: backend.url,
      TEST_DB_PROXY_CONTROL: backend.proxy?.control || "", TEST_DB_PROXY_TOKEN: backend.proxy?.token || "" },
  });
  const [code] = await once(child, "exit"); process.exitCode = code ?? 1;
} finally {
  process.off("SIGINT", stop); process.off("SIGTERM", stop); await backend.close();
}
