import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "tests",
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  reporter: [
    ["list"],
    ["json", { outputFile: "test-results/report.json" }],
  ],
  use: {
    baseURL: "http://127.0.0.1:4173",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command:
      "node --experimental-strip-types --disable-warning=ExperimentalWarning scripts/serve.ts --port 4173",
    url: "http://127.0.0.1:4173/",
    reuseExistingServer: false,
  },
});
