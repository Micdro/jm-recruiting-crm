import { test, expect } from '../support/fixtures.js';
import { ADMIN_EMAIL, ADMIN_PASSWORD } from '../support/urls.js';

// These tests start signed out.
test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Sign-in', () => {
  test('sends signed-out visitors to the sign-in page', async ({ page }) => {
    await page.goto('/contacts');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Recruiting CRM' })).toBeVisible();
  });

  test('shows an error for a wrong password', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Email').fill(ADMIN_EMAIL);
    await page.getByLabel('Password').fill('definitely-wrong-password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByRole('alert')).toHaveText('Invalid email or password');
  });

  test('signs in, returns to the page asked for, and signs out', async ({ page }) => {
    await page.goto('/companies');
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('Email').fill(ADMIN_EMAIL);
    await page.getByLabel('Password').fill(ADMIN_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/companies$/);
    await expect(page.getByRole('heading', { name: 'Companies', level: 1 })).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
