import { defineConfig } from "@playwright/test";

// The dispatcher's desk, against a production build with a mocked API.
export default defineConfig({
  testDir: "./tests/e2e-dispatcher",
  timeout: 45_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:43222",
    viewport: { width: 1440, height: 900 },
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43222" : "npm run start -- --port 43222",
    url: "http://127.0.0.1:43222/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
