import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/admin-browser",
  use: { baseURL: process.env.ADMIN_TEST_URL ?? "http://127.0.0.1:43219", channel: "chrome", headless: true },
  workers: 1,
  reporter: "list",
  webServer: process.env.ADMIN_TEST_URL ? undefined : { command: "node node_modules/next/dist/bin/next start --port 43219 --hostname 127.0.0.1", url: "http://127.0.0.1:43219/admin/demo", reuseExistingServer: !process.env.CI, timeout: 60000 },
});
