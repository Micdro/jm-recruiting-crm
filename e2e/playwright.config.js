import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';
import { API_URL, AUTH_FILE, UI_URL } from './tests/support/urls.js';

const isCI = !!process.env.CI;

// "Today" in the CRM follows the firm's time zone; the tests and the browser use the same one.
const TIME_ZONE = process.env.APP_TIMEZONE ?? 'America/New_York';
process.env.TZ = TIME_ZONE;
const backendDir = fileURLToPath(new URL('../backend', import.meta.url));
const frontendDir = fileURLToPath(new URL('../frontend', import.meta.url));

export default defineConfig({
  testDir: './tests',
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: isCI
    ? [['github'], ['list'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],

  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    // Signs in once and saves the session for everything else.
    {
      name: 'setup',
      testMatch: /auth\.setup\.js/,
    },
    {
      name: 'api',
      testDir: './tests/api',
      dependencies: ['setup'],
      use: { baseURL: API_URL, storageState: AUTH_FILE },
    },
    {
      name: 'ui',
      testDir: './tests/ui',
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], baseURL: UI_URL, storageState: AUTH_FILE, timezoneId: TIME_ZONE },
    },
  ],

  // Starts the backend and frontend unless they are already running
  // (for example from start-dev.bat locally, or the backend started by CI).
  webServer: [
    {
      command: process.platform === 'win32' ? 'mvnw.cmd spring-boot:run' : './mvnw spring-boot:run',
      cwd: backendDir,
      url: `${API_URL}/api/health`,
      reuseExistingServer: true,
      timeout: 180_000,
    },
    {
      command: 'npm run dev -- --strictPort',
      cwd: frontendDir,
      url: UI_URL,
      reuseExistingServer: !isCI,
      timeout: 60_000,
    },
  ],
});
