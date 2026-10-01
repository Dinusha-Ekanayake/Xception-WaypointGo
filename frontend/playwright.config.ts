import { defineConfig } from "@playwright/test";

// Browser tests run against a production build (`npm run build` first), because
// the service worker only exists there. The Next server proxies /api/* to
// BACKEND_URL; tests that need the backend start it themselves.
export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 60000,
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: "http://127.0.0.1:43219",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm start -- --port 43219",
    url: "http://127.0.0.1:43219/healthz",
    reuseExistingServer: false,
    timeout: 60000,
    env: {
      BACKEND_URL: process.env.BACKEND_URL ?? "http://127.0.0.1:8080",
    },
  },
});
