import { test, expect, uniqueEmail, uniqueName } from '../support/fixtures.js';

test.describe('Contacts', () => {
  test('adds a contact linked to a company', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const name = uniqueName('Priya Shah');
    const email = uniqueEmail('priya');
    crm.trackName('contacts', name);

    await page.goto('/contacts');
    await page.getByRole('button', { name: 'Add contact' }).click();

    const dialog = page.getByRole('dialog', { name: 'Add contact' });
    await dialog.getByLabel('Contact name').fill(name);
    await dialog.getByLabel('Title').fill('Head of ML Platform');
    await dialog.getByLabel('Company').selectOption({ label: company.name });
    await dialog.getByLabel('Email').fill(email);
    await dialog.getByRole('button', { name: 'Save contact' }).click();

    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    await expect(page.getByRole('link', { name: company.name })).toBeVisible();

    const saved = await crm.findContactByName(name);
    expect(saved).toMatchObject({ email, companyId: company.id, status: 'NEW', title: 'Head of ML Platform' });
  });

  test('logs a touch from the contact page', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);

    await page.goto(`/contacts/${contact.id}`);
    await expect(page.getByText('Touch 1 of 4. Next touch')).toBeVisible();

    await page.getByLabel('Notes').first().fill('Connection request about FDE hiring');
    await page.getByRole('button', { name: 'Log LinkedIn message' }).click();

    await expect(page.getByRole('status')).toContainText(`LinkedIn logged for ${contact.name}`);
    await expect(page.getByRole('img', { name: '1 of 4 touches' })).toBeVisible();
    await expect(page.getByText('Touch 1 of 4: Connection request about FDE hiring')).toBeVisible();

    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ touchCount: 1, status: 'IN_SEQUENCE' });
  });

  test('filters the list by status', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const replied = await crm.createContact(company.id, { status: 'REPLIED' });
    const fresh = await crm.createContact(company.id);

    await page.goto(`/contacts?q=${encodeURIComponent(company.name)}`);
    await expect(page.getByRole('link', { name: replied.name })).toBeVisible();
    await expect(page.getByRole('link', { name: fresh.name })).toBeVisible();

    await page.getByLabel('Status').selectOption({ label: 'Replied' });
    await expect(page.getByRole('link', { name: replied.name })).toBeVisible();
    await expect(page.getByRole('link', { name: fresh.name })).toHaveCount(0);
  });
});
