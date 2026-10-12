import { test, expect } from '../support/fixtures.js';

test.describe('Quick search', () => {
  test('Ctrl+K finds a contact by name and opens it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);

    await page.goto('/placements');
    await expect(page.getByRole('heading', { name: 'Placements', level: 1 })).toBeVisible();
    await page.keyboard.press('Control+k');
    const box = page.getByRole('combobox', { name: /Search contacts/ });
    await expect(box).toBeFocused();

    await box.fill(contact.name);
    await expect(page.getByRole('option', { name: new RegExp(contact.name) })).toBeVisible();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(new RegExp(`/contacts/${contact.id}$`));
    await expect(page.getByRole('heading', { name: contact.name, level: 1 })).toBeVisible();
    await expect(page.getByRole('dialog', { name: 'Quick search' })).toHaveCount(0);
  });

  test('the Search button finds a company by what it does, and Escape closes', async ({ page, crm }) => {
    const company = await crm.createCompany({ focus: 'Quokka ledger reconciliation' });

    await page.goto('/');
    await page.getByRole('button', { name: /^Search/ }).click();
    const box = page.getByRole('combobox', { name: /Search contacts/ });
    await box.fill('quokka ledger');
    const option = page.getByRole('option', { name: new RegExp(company.name) });
    await expect(option).toBeVisible();
    await option.click();
    await expect(page).toHaveURL(new RegExp(`/companies/${company.id}$`));

    await page.keyboard.press('Control+k');
    await expect(page.getByRole('dialog', { name: 'Quick search' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: 'Quick search' })).toHaveCount(0);
  });
});
