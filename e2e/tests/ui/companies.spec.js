import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Companies page', () => {
  test('adds a company from the form and lists it', async ({ page, crm }) => {
    const name = uniqueName('Northwind Bank');
    crm.trackCompanyName(name);

    await page.goto('/');
    const companies = page.locator('#companies');

    await companies.getByLabel('Company name').fill(name);
    await companies.getByLabel('Website').fill('https://northwind.example');
    await companies.getByLabel('Location').fill('Charlotte, NC');
    await companies.getByLabel('Company size').fill('1000+');
    await companies.getByLabel('Status').fill('Prospect');
    await companies.getByRole('button', { name: 'Save Company' }).click();

    const listItem = companies.locator('.company-list-item').filter({ hasText: name });
    await expect(listItem).toBeVisible();
    await expect(listItem).toContainText('Prospect');
    await expect(listItem).toContainText('Charlotte, NC');

    // Form resets, and the new company is selectable for contacts.
    await expect(companies.getByLabel('Company name')).toHaveValue('');
    await expect(
      page.locator('#contacts').getByRole('combobox').locator('option', { hasText: name }),
    ).toHaveCount(1);

    // Saved in the database, not just in page state.
    const saved = await crm.findCompanyByName(name);
    expect(saved).toMatchObject({ name, location: 'Charlotte, NC', status: 'Prospect' });
  });

  test('lists companies that already exist', async ({ page, crm }) => {
    const company = await crm.createCompany({ status: 'Client' });

    await page.goto('/');

    const listItem = page.locator('#companies .company-list-item').filter({ hasText: company.name });
    await expect(listItem).toBeVisible();
    await expect(listItem).toContainText('Client');
  });

  test('shows an error when the server rejects a new company', async ({ page }) => {
    await page.route('**/api/companies', (route) =>
      route.request().method() === 'POST'
        ? route.fulfill({ status: 500, body: 'Server error' })
        : route.continue(),
    );

    await page.goto('/');
    const companies = page.locator('#companies');

    await companies.getByLabel('Company name').fill(uniqueName('Failing Co'));
    await companies.getByRole('button', { name: 'Save Company' }).click();

    await expect(companies.locator('.error-message')).toHaveText(
      'Unable to create company. Check the form and make sure the backend is running.',
    );
    await expect(companies.getByRole('button', { name: 'Save Company' })).toBeEnabled();
  });
});
