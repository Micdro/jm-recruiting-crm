import { test, expect, isoDate, uniqueName } from '../support/fixtures.js';

test.describe('Bulk actions', () => {
  test('ticks a range of contacts, changes their status, and undoes it', async ({ page, crm }) => {
    const token = uniqueName('Bulk');
    const company = await crm.createCompany();
    const [a, , c] = [
      await crm.createContact(company.id, { name: `${token} A` }),
      await crm.createContact(company.id, { name: `${token} B` }),
      await crm.createContact(company.id, { name: `${token} C` }),
    ];

    await page.goto(`/contacts?q=${encodeURIComponent(token)}`);
    await page.getByRole('checkbox', { name: `Tick ${a.name}` }).check();
    await page.getByRole('checkbox', { name: `Tick ${c.name}` }).click({ modifiers: ['Shift'] });

    const bar = page.getByRole('region', { name: 'Bulk actions' });
    await expect(bar).toContainText('3 contacts ticked');

    await bar.getByRole('button', { name: 'Change status' }).click();
    await bar.getByRole('group', { name: 'Change status to' }).getByRole('button', { name: 'Nurture' }).click();

    const toast = page.locator('.toast-region');
    await expect(toast).toContainText('3 contacts set to Nurture.');
    await expect(bar).toHaveCount(0);
    expect((await crm.getJson(`/api/contacts/${c.id}`)).status).toBe('NURTURE');

    await toast.getByRole('button', { name: 'Undo' }).click();
    await expect(toast).toContainText('Undone for 3 contacts.');
    expect((await crm.getJson(`/api/contacts/${c.id}`)).status).toBe('NEW');
  });

  test('ticks every company shown, sets follow-ups, and names what it could not delete', async ({ page, crm }) => {
    const token = uniqueName('Bulk Co');
    const busy = await crm.createCompany({ name: `${token} Busy` });
    await crm.createContact(busy.id);
    const empty = await crm.createCompany({ name: `${token} Empty` });

    await page.goto(`/companies?q=${encodeURIComponent(token)}`);
    await page.getByRole('checkbox', { name: 'Tick all 2 companies shown' }).check();
    const bar = page.getByRole('region', { name: 'Bulk actions' });
    await bar.getByRole('button', { name: 'Follow up today' }).click();

    await expect(page.locator('.toast-region')).toContainText('2 companies set to follow up today.');
    expect((await crm.getJson(`/api/companies/${empty.id}`)).followUpDate).toBe(isoDate(0));

    await page.getByRole('checkbox', { name: 'Tick all 2 companies shown' }).check();
    await bar.getByRole('button', { name: 'Delete' }).click();
    await bar.getByRole('button', { name: 'Delete 2' }).click();

    await expect(page.locator('.toast-region')).toContainText('1 company to the Trash.');
    const note = page.locator('.bulk-note');
    await expect(note).toContainText('Has contacts; delete or move them first');
    await expect(note).toContainText(busy.name);
    expect((await crm.api.get(`/api/companies/${empty.id}`)).status()).toBe(404);
    await expect(page.getByRole('link', { name: busy.name })).toBeVisible();
  });
});
