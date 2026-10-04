import { defineConfig } from "@playwright/test";

// The dispatcher's desk, against a production build with a mocked API.
export default defineConfig({
  testDir: "./tests/e2e-dispatcher",
  timeout: 45_000,
  // Two in CI, where each suite has a runner to itself.
  workers: process.env.CI ? 2 : 1,
  // In CI (#120): one retry, reported as flaky rather than hidden; failures as
  // annotations on the pull request, and a report per suite for the artifact.
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["list"], ["html", { open: "never", outputFolder: "playwright-report/dispatcher" }]] : "list",
  outputDir: "test-results/dispatcher",
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
