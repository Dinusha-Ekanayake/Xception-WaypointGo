import { defineConfig } from "@playwright/test";
if (!process.env.DATABASE_SCHEMA?.startsWith("waypoint_test_")) {
  throw new Error("Use npm run test:e2e with a dedicated TEST_DATABASE_URL.");
}
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
    url: "http://127.0.0.1:43219/api/health",
    reuseExistingServer: false,
    timeout: 60000,
    env: {
      DATABASE_URL: process.env.DATABASE_URL!,
      DATABASE_SCHEMA: process.env.DATABASE_SCHEMA!,
      DEMO_MODE: "1",
      SEED_PASSWORD: "Waypoint2026!",
      COOKIE_SECURE: "0",
    },
  },
});
