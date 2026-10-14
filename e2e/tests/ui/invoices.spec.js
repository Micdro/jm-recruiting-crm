import fs from 'node:fs';
import { test, expect } from '../support/fixtures.js';

test.describe('Invoices', () => {
  test('creates an invoice for a placement and downloads the PDF', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id, { feePercent: 25 });
    const candidate = await crm.createCandidate();
    await crm.createPlacement({ searchId: search.id, candidateId: candidate.id, startDate: '2026-11-02', baseSalary: 200000 });

    await page.goto('/placements');
    const row = page.getByRole('row').filter({ hasText: candidate.name });
    await row.getByRole('button', { name: 'Create invoice' }).click();
    const dialog = page.getByRole('dialog');

    // The firm's details, the first time.
    const firmName = dialog.getByLabel('Firm name');
    await expect(firmName.or(dialog.getByLabel('Invoice number'))).toBeVisible();
    if (await firmName.isVisible()) {
      await dialog.getByLabel('Address').fill('100 Example Street\nTampa, FL 33602');
      await dialog.getByLabel('Billing email').fill('billing@jane-michael.test');
      await dialog.getByLabel('How to pay').fill('ACH to Jane Michael LLC, routing 000000000');
      await dialog.getByRole('button', { name: 'Save and continue' }).click();
    }

    await expect(dialog.getByLabel('Invoice number')).toHaveValue(/^\d{4}-\d{3}$/);
    await expect(dialog.getByText('$50,000')).toBeVisible();
    await expect(dialog.getByLabel('Bill to')).toHaveValue(company.name);
    await dialog.getByLabel('Billing email').fill('ap@example.test');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      dialog.getByRole('button', { name: 'Create invoice and download PDF' }).click(),
    ]);

    expect(download.suggestedFilename()).toMatch(/^Invoice \d{4}-\d{3} .+\.pdf$/);
    expect(fs.readFileSync(await download.path()).subarray(0, 5).toString()).toBe('%PDF-');
    await expect(page.getByRole('status')).toContainText('saved and downloaded. Send it to ap@example.test.');
    await expect(row.getByRole('button', { name: /^Invoice \d{4}-\d{3}$/ })).toBeVisible();
    await expect(row).toContainText('Invoiced');
  });
});
