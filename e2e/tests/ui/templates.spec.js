import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Message templates', () => {
  test('writes the next touch from a template, copies it and logs it', async ({ page, context, crm }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);
    const template = await crm.createTemplate({
      name: uniqueName('Connection note'),
      channel: 'LINKEDIN',
      touch: 1,
      body: 'Hi {first name}, how is {company} staffing its deployments?',
    });
    const firstName = contact.name.split(' ')[0];
    const expected = `Hi ${firstName}, how is ${company.name} staffing its deployments?`;

    await page.goto(`/contacts/${contact.id}`);
    await page.getByRole('button', { name: 'Write message' }).click();

    const dialog = page.getByRole('dialog', { name: `Message for ${contact.name}` });
    await expect(dialog.getByText('Touch 1 of 4.')).toBeVisible();
    const choice = dialog.getByLabel('Template');
    if (await choice.count()) {
      await choice.selectOption({ label: `${template.name} (touch 1)` });
    }
    await expect(dialog.getByLabel('Message')).toHaveValue(expected);

    await dialog.getByRole('button', { name: 'Copy message' }).click();
    await expect(dialog.getByRole('button', { name: 'Copied' })).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected);

    await dialog.getByRole('button', { name: 'Log LinkedIn message as sent' }).click();
    await expect(page.getByRole('status')).toContainText('LinkedIn logged.');
    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ touchCount: 1, status: 'IN_SEQUENCE' });
  });

  test('adds a template with placeholders and previews it', async ({ page, crm }) => {
    const name = uniqueName('Follow-up');

    await page.goto('/templates');
    await page.getByRole('button', { name: 'Add template' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add template' });
    await dialog.getByLabel('Name').fill(name);
    await dialog.getByLabel('Touch').selectOption({ label: 'Touch 3 (follow-up)' });
    await dialog.getByText('LinkedIn', { exact: true }).click();
    await dialog.getByLabel('Message').fill('Hi {first name}, checking in about ');
    await dialog.getByRole('button', { name: '{company}' }).click();
    await expect(dialog.getByText('Hi Dana, checking in about Ledgerlight AI')).toBeVisible();
    await dialog.getByRole('button', { name: 'Add template' }).click();

    await expect(page.getByRole('status')).toContainText(`Added ${name}`);
    const saved = (await crm.getJson('/api/templates')).find((template) => template.name === name);
    crm.track('templates', saved.id);
    expect(saved).toMatchObject({ channel: 'LINKEDIN', touch: 3, body: 'Hi {first name}, checking in about {company}' });
  });

  test('offers the LinkedIn starter for a touch that only has an email template', async ({ page, crm }) => {
    const email = await crm.createTemplate({ name: uniqueName('Old email'), channel: 'EMAIL', touch: 4, subject: 'Hello', body: 'Hi' });

    await page.goto('/templates');
    const touch = page.getByRole('region', { name: /Touch 4/ });
    await expect(touch).toContainText('Last message on LinkedIn');
    await expect(touch).toContainText(email.name);
    await expect(touch).toContainText('No LinkedIn message for this touch yet.');
    await touch.getByRole('button', { name: 'Add the starter message for touch 4' }).click();

    await expect(page.getByRole('status')).toContainText('Added Last message.');
    await expect(touch).not.toContainText('No LinkedIn message for this touch yet.');
    const added = (await crm.getJson('/api/templates')).filter((template) => template.touch === 4 && template.channel === 'LINKEDIN');
    added.forEach((template) => crm.track('templates', template.id));
    expect(added).toHaveLength(1);
    expect(added[0]).toMatchObject({ name: 'Last message', subject: null });
    expect(added[0].body).toContain('reply here');
  });
});
