import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Merging duplicates', () => {
  test('shows contacts that look like duplicates and merges two of them', async ({ page, crm }) => {
    const name = uniqueName('Twin');
    const company = await crm.createCompany();
    const older = await crm.createContact(company.id, { name, title: 'VP Engineering', email: null });
    const newer = await crm.createContact(company.id, { name, title: null });

    await page.goto(`/contacts?q=${encodeURIComponent(name)}`);
    await expect(page.getByText('2 contacts look like duplicates')).toBeVisible();
    await page.getByRole('button', { name: 'Show them' }).click();

    await page.getByRole('checkbox', { name: `Tick all 2 contacts shown` }).check();
    const bar = page.getByRole('region', { name: 'Bulk actions' });
    await bar.getByRole('button', { name: 'Merge' }).click();

    const dialog = page.getByRole('dialog');
    // The older record is kept unless you choose the other.
    await expect(dialog.getByRole('radio', { checked: true })).toHaveCount(1);
    await expect(dialog).toContainText(newer.email);
    await dialog.getByRole('button', { name: `Merge into ${name}` }).click();

    await expect(page.locator('.toast-region')).toContainText(`Merged ${name} into ${name}.`);
    expect((await crm.api.get(`/api/contacts/${newer.id}`)).status()).toBe(404);
    expect(await crm.getJson(`/api/contacts/${older.id}`)).toMatchObject({ title: 'VP Engineering', email: newer.email });
    await expect(page.getByText('No contacts look like duplicates now.')).toBeVisible();
  });
});
