import { test, expect, isoDate, laterFrom } from '../support/fixtures.js';

test.describe('Today', () => {
  test('restarts a sequence that came back with no reply, and undoes it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      status: 'NO_RESPONSE',
      nextActionDate: isoDate(0),
      nextActionType: 'LINKEDIN',
    });

    await page.goto('/');
    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await expect(row).toContainText('Revisit');
    await expect(row.getByRole('button', { name: `Write a message to ${contact.name}` })).toHaveCount(0);

    await row.getByRole('button', { name: `Restart the sequence for ${contact.name}` }).click();
    const toast = page.locator('.toast-region');
    await expect(toast).toContainText(`Restarted ${contact.name}'s sequence. Touch 1 is due today.`);
    await expect(row).toContainText('Touch 1');
    expect(await crm.getJson(`/api/contacts/${contact.id}`)).toMatchObject({ status: 'NEW', touchCount: 0, nextActionDate: isoDate(0) });

    await toast.getByRole('button', { name: 'Undo' }).click();
    await expect(row).toContainText('Revisit');
    expect((await crm.getJson(`/api/contacts/${contact.id}`)).status).toBe('NO_RESPONSE');
  });

  test('undoes a touch logged by mistake', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, { nextActionDate: isoDate(0), nextActionType: 'LINKEDIN' });

    await page.goto('/');
    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await row.getByRole('button', { name: `Log LinkedIn for ${contact.name}` }).click();

    const toast = page.getByRole('status');
    await expect(toast).toContainText(`LinkedIn logged for ${contact.name}`);
    await expect(row).toHaveCount(0);

    await toast.getByRole('button', { name: 'Undo' }).click();
    await expect(toast).toContainText('Undone.');
    await expect(row).toBeVisible();
    await expect(row).toContainText('Touch 1');

    expect(await crm.getJson(`/api/contacts/${contact.id}`)).toMatchObject({
      touchCount: 0,
      status: 'NEW',
      nextActionDate: isoDate(0),
    });
    expect(await crm.getJson(`/api/activities?contactId=${contact.id}`)).toHaveLength(0);
  });

  test('moves a contact to later from the queue, and back with Undo', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      status: 'IN_SEQUENCE',
      nextActionDate: isoDate(0),
      nextActionType: 'LINKEDIN',
    });
    const { today } = await crm.getJson('/api/dashboard');

    await page.goto('/');
    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await row.getByRole('button', { name: `Move ${contact.name} to later` }).click();
    await page
      .getByRole('group', { name: `Move ${contact.name} to` })
      .getByRole('button', { name: /^Tomorrow, / })
      .click();

    const toast = page.getByRole('status');
    await expect(toast).toContainText(`Moved ${contact.name} to`);
    await expect(row).toHaveCount(0);
    expect(await crm.getJson(`/api/contacts/${contact.id}`)).toMatchObject({
      nextActionDate: laterFrom(today, 1),
      nextActionType: 'LINKEDIN',
      status: 'IN_SEQUENCE',
    });

    await toast.getByRole('button', { name: 'Undo' }).click();
    await expect(row).toBeVisible();
    expect((await crm.getJson(`/api/contacts/${contact.id}`)).nextActionDate).toBe(isoDate(0));
  });

  test('moves a client follow-up to next week', async ({ page, crm }) => {
    const company = await crm.createCompany({ followUpDate: isoDate(0) });
    const { today } = await crm.getJson('/api/dashboard');

    await page.goto('/');
    await page.getByRole('button', { name: `Move ${company.name} to later` }).click();
    await page
      .getByRole('group', { name: `Move ${company.name} to` })
      .getByRole('button', { name: /^Next week, / })
      .click();

    await expect(page.getByRole('status')).toContainText(`Moved ${company.name} to`);
    await expect(page.getByRole('button', { name: `Move ${company.name} to later` })).toHaveCount(0);
    expect((await crm.getJson(`/api/companies/${company.id}`)).followUpDate).toBe(laterFrom(today, 7));
  });

  test('logs a touch from the queue and moves the contact off it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      title: 'CISO',
      nextActionDate: isoDate(0),
      nextActionType: 'LINKEDIN',
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Today', level: 1 })).toBeVisible();
    await expect(page.getByRole('meter').first()).toBeVisible();

    const row = page.getByTestId('queue-contact').filter({ hasText: contact.name });
    await expect(row).toBeVisible();
    await expect(row).toContainText('Touch 1');
    await expect(row).toContainText('Today');

    await expect(row.getByRole('button', { name: `Log Email for ${contact.name}` })).toHaveCount(0);
    await row.getByRole('button', { name: `Log LinkedIn for ${contact.name}` }).click();

    await expect(page.getByRole('status')).toContainText(`LinkedIn logged for ${contact.name}`);
    await expect(row).toHaveCount(0);

    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ touchCount: 1, status: 'IN_SEQUENCE', nextActionType: 'LINKEDIN' });
    expect(saved.nextActionDate > isoDate(0)).toBeTruthy();
  });

  test('shows the day’s affirmation under the date', async ({ page }) => {
    await page.goto('/');
    const affirmation = page.getByTestId('affirmation');
    await expect(affirmation).toBeVisible();
    await expect(affirmation).toHaveText(/\w.*[.”]$/);

    await page.reload();
    const first = await affirmation.textContent();
    await page.reload();
    await expect(page.getByTestId('affirmation')).toHaveText(first);
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
    expect(saved).toMatchObject({ status: 'REPLIED', nextActionDate: isoDate(0), nextActionType: 'LINKEDIN' });
  });

  test('shows a placement recorded this week to the whole team', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    await crm.createPlacement({ searchId: search.id, candidateId: candidate.id, baseSalary: 180000 });

    await page.goto('/');
    const placed = page.getByRole('region', { name: 'Placed this week' });
    await expect(placed.getByRole('link', { name: candidate.name })).toBeVisible();
    await expect(placed).toContainText(company.name);
  });
});
