import { defineConfig } from "@playwright/test";
import { deviceProjects } from "./tests/devices.ts";

export default defineConfig({
  testDir: "./tests/e2e-loader",
  testMatch: ["offline.spec.ts", "release.spec.ts", "language.spec.ts", "offline-pin.spec.ts", "wide.spec.ts", "held.spec.ts", "notifications.spec.ts", "devices.spec.ts"],
  timeout: 30_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:43220",
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  projects: deviceProjects({ width: 393, height: 852 }),
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43220" : "npm run start -- --port 43220",
    url: "http://127.0.0.1:43220/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
