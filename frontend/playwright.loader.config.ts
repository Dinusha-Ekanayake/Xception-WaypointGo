import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e-loader",
  testMatch: ["offline.spec.ts", "release.spec.ts"],
  timeout: 30_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:43220",
    viewport: { width: 393, height: 852 },
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43220" : "npm run start -- --port 43220",
    url: "http://127.0.0.1:43220/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
