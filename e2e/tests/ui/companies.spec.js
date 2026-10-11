import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Companies', () => {
  test('adds a company and opens its page', async ({ page, crm }) => {
    const name = uniqueName('Ledgerlight AI');
    crm.trackName('companies', name);

    await page.goto('/companies');
    await page.getByRole('button', { name: 'Add company' }).click();

    const dialog = page.getByRole('dialog', { name: 'Add company' });
    await dialog.getByLabel('Company name').fill(name);
    await dialog.getByLabel('Focus').fill('AI model risk monitoring for insurers');
    await dialog.getByLabel('Stage').selectOption({ label: 'Initial outreach' });
    await dialog.getByLabel('Priority').selectOption({ label: 'High' });
    await dialog.getByLabel('Compliance and AML').check();
    await dialog.getByLabel('Location').fill('Boston, MA');
    await dialog.getByRole('button', { name: 'Save company' }).click();

    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    await expect(page.getByText('AI model risk monitoring for insurers')).toBeVisible();
    await expect(page.getByText('Initial outreach', { exact: true })).toBeVisible();
    await expect(page.getByText('Compliance and AML')).toBeVisible();

    const saved = await crm.findCompanyByName(name);
    expect(saved).toMatchObject({ name, status: 'INITIAL_OUTREACH', priority: 'HIGH', location: 'Boston, MA' });
  });

  test('lists companies and filters by name', async ({ page, crm }) => {
    const company = await crm.createCompany({ location: 'Charlotte, NC' });

    await page.goto('/companies');
    await page.getByPlaceholder('Search name, focus or location').fill(company.name);

    const row = page.getByRole('row', { name: new RegExp(company.name) });
    await expect(row).toBeVisible();
    await expect(row).toContainText('Charlotte, NC');
    await expect(page.getByRole('row')).toHaveCount(2);
  });

  test('explains why a company with contacts can’t be deleted', async ({ page, crm }) => {
    const company = await crm.createCompany();
    await crm.createContact(company.id);

    await page.goto(`/companies/${company.id}`);
    await page.getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('button', { name: 'Confirm delete' }).click();

    await expect(page.getByRole('alert')).toContainText('Company has contacts');
  });
});
