import { test, expect } from '../support/fixtures.js';

test.describe('Trash', () => {
  test('Undo right after deleting a contact brings it back', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);

    await page.goto(`/contacts/${contact.id}`);
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm delete' }).click();
    await expect(page).toHaveURL(/\/contacts$/);
    const toast = page.getByRole('status');
    await expect(toast).toContainText(`Deleted ${contact.name}`);

    await toast.getByRole('button', { name: 'Undo' }).click();
    await expect(page).toHaveURL(new RegExp(`/contacts/${contact.id}$`));
    await expect(page.getByRole('heading', { name: contact.name, level: 1 })).toBeVisible();
    await expect(toast).toContainText(`Restored ${contact.name}`);
  });

  test('a deleted company waits in the Trash and can be restored from there', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const deleted = await crm.api.delete(`/api/companies/${company.id}`);
    expect(deleted.status()).toBe(204);

    await page.goto('/');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Trash', exact: true }).click();
    const item = page.getByRole('listitem').filter({ hasText: company.name });
    await expect(item).toContainText('Company');
    await expect(item).toContainText('Removed for good');
    await item.getByRole('button', { name: 'Restore' }).click();
    await expect(page.getByRole('status')).toContainText(`Restored ${company.name}`);
    await expect(item).toHaveCount(0);

    const back = await crm.getJson(`/api/companies/${company.id}`);
    expect(back.name).toBe(company.name);
  });
});
