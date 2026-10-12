import fs from 'node:fs';
import { test, expect } from '../support/fixtures.js';

test.describe('Export to Excel', () => {
  test('exports the contacts a filter shows', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, { notes: 'Prefers email' });

    await page.goto(`/contacts?q=${encodeURIComponent(contact.name)}`);
    await expect(page.getByText(contact.name).first()).toBeVisible();
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Export', exact: true }).click(),
    ]);

    expect(download.suggestedFilename()).toMatch(/^JM CRM contacts \d{4}-\d{2}-\d{2}\.xlsx$/);
    // An .xlsx file is a zip archive.
    expect(fs.readFileSync(await download.path()).subarray(0, 2).toString()).toBe('PK');
    await expect(page.getByRole('status')).toContainText('Exported 1 contact to Excel');
  });

  test('exports everything from the Export page', async ({ page, crm }) => {
    const only = await crm.getJson('/api/export?include=contacts');
    expect(only.companies).toBeNull();
    expect(Array.isArray(only.contacts)).toBeTruthy();

    await page.goto('/');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Export', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Export', level: 1 })).toBeVisible();
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Download Excel file' }).click(),
    ]);

    expect(download.suggestedFilename()).toMatch(/^JM CRM export \d{4}-\d{2}-\d{2}\.xlsx$/);
    await expect(page.getByRole('status')).toContainText('Exported everything to Excel');
  });
});
