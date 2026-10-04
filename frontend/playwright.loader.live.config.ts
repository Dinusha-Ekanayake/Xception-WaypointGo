import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e-loader",
  testMatch: "live.spec.ts",
  timeout: 60_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: process.env.LOADER_LIVE_BASE_URL,
    viewport: { width: 393, height: 852 },
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
});
