import { test, expect, isoDate } from '../support/fixtures.js';

test.describe('Today', () => {
  test('logs a touch from the queue and moves the contact off it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      title: 'CISO',
      nextActionDate: isoDate(0),
      nextActionType: 'EMAIL',
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Today', level: 1 })).toBeVisible();
    await expect(page.getByRole('meter').first()).toBeVisible();

    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await expect(row).toBeVisible();
    await expect(row).toContainText('Touch 1');
    await expect(row).toContainText('Today');

    await row.getByRole('button', { name: `Log Email for ${contact.name}` }).click();

    await expect(page.getByRole('status')).toContainText(`Email logged for ${contact.name}`);
    await expect(row).toHaveCount(0);

    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ touchCount: 1, status: 'IN_SEQUENCE' });
    expect(saved.nextActionDate > isoDate(0)).toBeTruthy();
  });

  test('logs a reply with notes from the queue', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      status: 'IN_SEQUENCE',
      nextActionDate: isoDate(-2),
      nextActionType: 'LINKEDIN',
    });

    await page.goto('/');
    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await expect(row).toContainText('Overdue 2 days');

    await row.getByRole('button', { name: `More options for ${contact.name}` }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByText('Reply', { exact: true }).click();
    await dialog.getByLabel('Notes').fill('Asked for a call next week');
    await dialog.getByRole('button', { name: 'Log reply' }).click();

    await expect(dialog).toHaveCount(0);
    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ status: 'REPLIED', nextActionDate: isoDate(0) });
  });
});
