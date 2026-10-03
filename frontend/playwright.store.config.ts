import { defineConfig } from "@playwright/test";
import { deviceProjects } from "./tests/devices.ts";

// The store manager's browser tests, on the desktop layout of Figma "1 · Main
// flow", against a production build with a mocked API (tests/e2e-store/mocks.ts).

export default defineConfig({
  testDir: "./tests/e2e-store",
  testMatch: ["*.spec.ts"],
  timeout: 30_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:43223",
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  projects: deviceProjects({ width: 1440, height: 900 }),
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43223" : "npm run start -- --port 43223",
    url: "http://127.0.0.1:43223/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
