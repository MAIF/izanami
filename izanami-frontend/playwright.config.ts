import { defineConfig, devices } from "@playwright/test";
import path from "path";

export const STORAGE_STATE = path.join(__dirname, "playwright/.auth/user.json");
/**
 * Read environment variables from file.
 * https://github.com/motdotla/dotenv
 */
// require('dotenv').config();

/**
 * See https://playwright.dev/docs/test-configuration.
 */
export default defineConfig({
  timeout: 120_000,
  testDir: "./tests",
  testIgnore: ["./tests/unit/**/*.spec.ts"],
  /* Run tests in files in parallel */
  fullyParallel: false,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: 3,
  /* Opt out of parallel tests on CI. */
  workers: 1,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: "html",
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('/')`. */
    // baseURL: 'http://127.0.0.1:3000',

    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: "on-first-retry",
    baseURL: "http://localhost:3000",
  },

  /* Configure projects for major browsers */
  // See https://playwright.dev/docs/test-global-setup-teardown for global & storageState
  projects: [
    {
      name: "setup",
      testMatch: /global.setup\.ts/,
    },
    {
      name: "chromium",
      dependencies: ["setup"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: STORAGE_STATE,
        contextOptions: {
          permissions: ["clipboard-read", "clipboard-write"],
        },
      },
      fullyParallel: false,
    },

    /*{
      name: "firefox",
      dependencies: ["setup"],
      use: { ...devices["Desktop Firefox"], storageState: STORAGE_STATE },
    },

    {
      name: "webkit",
      dependencies: ["setup"],
      use: { ...devices["Desktop Safari"], storageState: STORAGE_STATE },
    },*/

    /* Test against mobile viewports. */
    // {
    //   name: 'Mobile Chrome',
    //   use: { ...devices['Pixel 5'] },
    // },
    // {
    //   name: 'Mobile Safari',
    //   use: { ...devices['iPhone 12'] },
    // },

    /* Test against branded browsers. */
    // {
    //   name: 'Microsoft Edge',
    //   use: { ...devices['Desktop Edge'], channel: 'msedge' },
    // },
    // {
    //   name: 'Google Chrome',
    //   use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    // },
  ],

  /* Run your local dev server before starting the tests */
  webServer: process.env.CI
    ? {
        command: `PLAY_HTTP_PORT=3000 java -jar -Dconfig.resource=base-test.conf -Dapp.exposition.backend="http://localhost:3000" ../target/izanami.jar`,
        url: "http://127.0.0.1:3000",
        reuseExistingServer: true,
        stderr: "pipe",
        stdout: "pipe",
      }
    : [{
        command: `cd .. && docker-compose up > /dev/null`,
        url: "http://localhost:5001",
        reuseExistingServer: !process.env.CI,
        stderr: "pipe",
        stdout: "pipe",
        gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
        name: "Containers"
    }, {
        command: `cd .. && sbt "run -Dconfig.resource=base-test.conf -Dlogger.file=./test/resources/logback.xml"`,
        url: "http://127.0.0.1:9000/api/_health",
        reuseExistingServer: false, // Test conf is different from dev conf
        stderr: "pipe",
        stdout: "pipe",
        name: "Backend"
    }, {
        command: `npm run dev`,
        url: "http://localhost:3000",
        reuseExistingServer: !process.env.CI,
        stderr: "pipe",
        stdout: "pipe",
        name: "Frontend"
    }],
});

/*
[{
        command: `cd .. && sbt "run -Dconfig.resource=base-test.conf"`,
        url: "http://127.0.0.1:9000",
        reuseExistingServer: true,
        stderr: "pipe",
        stdout: "pipe",
    }, {
        command: `npm run dev`,
        url: "http://localhost:3000",
        reuseExistingServer: true,
        stderr: "pipe",
        stdout: "pipe",
    }]

    */  

/*
  npx playwright test
    Runs the end-to-end tests.

  npx playwright test --ui
    Starts the interactive UI mode.

  npx playwright test --project=chromium
    Runs the tests only on Desktop Chrome.

  npx playwright test example
    Runs the tests in a specific file.

  npx playwright test --debug
    Runs the tests in debug mode.

  npx playwright codegen
    Auto generate tests with Codegen.
*/
