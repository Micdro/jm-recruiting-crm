import { test, expect } from '../support/fixtures.js';

// These tests never point a company at a real job board, so nothing leaves the test machine.
test.describe('Hiring signals', () => {
  test('a company page takes a job board link, and a company can be marked as having none', async ({ page, crm }) => {
    const company = await crm.createCompany();

    await page.goto(`/companies/${company.id}`);
    const panel = page.getByRole('region', { name: 'Open roles' });
    await expect(panel).toContainText('No job board on file');

    await panel.getByLabel('Job board link').fill('https://acme.example/careers');
    await panel.getByRole('button', { name: 'Save board' }).click();
    await expect(panel.getByRole('alert')).toContainText('Greenhouse, Lever or Ashby');

    await panel.getByRole('button', { name: /use one of these/ }).click();
    await expect(page.getByRole('status')).toContainText('Marked as having no job board');
    await expect(panel).toContainText('Marked as having no Greenhouse, Lever or Ashby board');

    const board = await crm.getJson(`/api/signals/companies/${company.id}`);
    expect(board.provider).toBe('NONE');
  });

  test('the Hiring signals page opens from the menu', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Hiring signals' }).click();
    await expect(page.getByRole('heading', { name: 'Hiring signals', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Check boards now' })).toBeVisible();
    await expect(page.getByRole('radio', { name: /New roles/ })).toBeChecked();
  });
});
