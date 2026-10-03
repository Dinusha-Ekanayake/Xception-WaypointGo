import { defineConfig } from "@playwright/test";

// The driver's phone, against a production build with a mocked API: the
// service worker only exists there, and a reload with no signal needs it.
export default defineConfig({
  testDir: "./tests/e2e-driver",
  timeout: 45_000,
  workers: 1,
  // In CI (#120): one retry, reported as flaky rather than hidden; failures as
  // annotations on the pull request, and a report per suite for the artifact.
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["list"], ["html", { open: "never", outputFolder: "playwright-report/driver" }]] : "list",
  outputDir: "test-results/driver",
  use: {
    baseURL: "http://127.0.0.1:43221",
    viewport: { width: 393, height: 852 },
    browserName: "chromium",
    channel: process.platform === "win32" ? "msedge" : undefined,
    headless: true,
  },
  webServer: {
    command: process.platform === "win32" ? "npm.cmd start -- --port 43221" : "npm run start -- --port 43221",
    url: "http://127.0.0.1:43221/",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
