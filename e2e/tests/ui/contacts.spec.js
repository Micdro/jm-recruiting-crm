import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Contacts page', () => {
  test('adds a contact linked to a company', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const name = uniqueName('Priya Shah');
    crm.trackContactName(name);

    await page.goto('/');
    const contacts = page.locator('#contacts');

    await contacts.getByLabel('Contact name').fill(name);
    await contacts.getByRole('combobox').selectOption({ label: company.name });
    await contacts.getByLabel('Title').fill('Head of ML Platform');
    await contacts.getByLabel('Email').fill('priya.shah@acme.example');
    await contacts.getByLabel('Relationship status').fill('Warm');
    await contacts.getByLabel('Next follow-up date').fill('2026-11-02');
    await contacts.getByRole('button', { name: 'Save Contact' }).click();

    const listItem = contacts.locator('.contact-list-item').filter({ hasText: name });
    await expect(listItem).toBeVisible();
    await expect(listItem).toContainText('Head of ML Platform');
    await expect(listItem).toContainText(company.name);
    await expect(listItem).toContainText('Status: Warm');
    await expect(listItem).toContainText('Next follow-up: 2026-11-02');

    await expect(contacts.getByLabel('Contact name')).toHaveValue('');

    // Saved in the database and linked to the right company.
    const saved = await crm.findContactByName(name);
    expect(saved).toMatchObject({
      name,
      email: 'priya.shah@acme.example',
      nextFollowUpDate: '2026-11-02',
      companyId: company.id,
      companyName: company.name,
    });
  });

  test('lists contacts that already exist with their company', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, { title: 'CISO' });

    await page.goto('/');

    const listItem = page.locator('#contacts .contact-list-item').filter({ hasText: contact.name });
    await expect(listItem).toBeVisible();
    await expect(listItem).toContainText('CISO');
    await expect(listItem).toContainText(company.name);
  });
});
