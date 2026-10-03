import { defineConfig } from "@playwright/test";
import { deviceProjects } from "./tests/devices.ts";

// The driver's phone, against a production build with a mocked API: the
// service worker only exists there, and a reload with no signal needs it.
export default defineConfig({
  testDir: "./tests/e2e-driver",
  timeout: 45_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:43221",
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  projects: deviceProjects({ width: 393, height: 852 }),
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43221" : "npm run start -- --port 43221",
    url: "http://127.0.0.1:43221/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
