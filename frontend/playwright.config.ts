import { defineConfig } from "@playwright/test";

const integration = process.argv.some((value) => value.includes("integration"));
if (integration && !process.env.KOREN_E2E_ENV)
  throw new Error(
    "Integration requires KOREN_E2E_ENV pointing to the private synthetic stand env file.",
  );
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: process.env.KOREN_E2E_URL || "http://127.0.0.1:5173",
    viewport: { width: 390, height: 844 },
    launchOptions: { executablePath: process.env.CHROMIUM_PATH },
    trace: "off",
    screenshot: "only-on-failure",
  },
  webServer: process.env.KOREN_E2E_URL
    ? undefined
    : {
        command: "npm run dev",
        url: "http://127.0.0.1:5173",
        reuseExistingServer: !process.env.CI,
      },
  projects: [
    { name: "ui", testMatch: "ui.spec.ts" },
    { name: "integration", testMatch: "integration.spec.ts" },
  ],
});
