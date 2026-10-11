import { test as setup, expect } from '@playwright/test';
import { ADMIN_EMAIL, ADMIN_PASSWORD, API_URL, AUTH_FILE } from './support/urls.js';
import { xsrfToken } from './support/session.js';

// Signs in once through the real login endpoint and saves the session for the other tests.
setup('sign in as the test admin', async ({ request }) => {
  await request.get(`${API_URL}/api/auth/csrf`);

  const login = await request.post(`${API_URL}/api/auth/login`, {
    form: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrfToken(request) },
  });
  expect(
    login.status(),
    `Sign-in failed for ${ADMIN_EMAIL}. Start the backend with ADMIN_EMAIL and ADMIN_PASSWORD set, or set them to an existing account.`,
  ).toBe(204);

  // Signing in rotates the CSRF token; this request issues the new one.
  const me = await request.get(`${API_URL}/api/auth/me`);
  expect(me.ok()).toBeTruthy();

  await request.storageState({ path: AUTH_FILE });
});
