import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';
import { API_URL, UI_URL } from './tests/support/urls.js';

const isCI = !!process.env.CI;
const backendDir = fileURLToPath(new URL('../backend', import.meta.url));
const frontendDir = fileURLToPath(new URL('../frontend', import.meta.url));

export default defineConfig({
  testDir: './tests',
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: isCI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],

  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'api',
      testDir: './tests/api',
      use: { baseURL: API_URL },
    },
    {
      name: 'ui',
      testDir: './tests/ui',
      use: { ...devices['Desktop Chrome'], baseURL: UI_URL },
    },
  ],

  // Starts the backend and frontend if they are not already running.
  // Locally, if start-dev.bat already has them up, the running servers are reused.
  webServer: [
    {
      command: process.platform === 'win32' ? 'mvnw.cmd spring-boot:run' : './mvnw spring-boot:run',
      cwd: backendDir,
      url: `${API_URL}/api/companies`,
      reuseExistingServer: !isCI,
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
