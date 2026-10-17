import { test, expect } from '../support/fixtures.js';

const FOUND_NOTES = [
  'Found by the prospect finder on October 16, 2026.',
  'Why now: Hiring two forward deployed engineers for a new bank rollout.',
  'What they do: Fraud models for credit unions.',
  'Sources:\nhttps://jobs.example/northwind-fde',
].join('\n\n');

test.describe('AI Found', () => {
  test('a found company waits on AI Found, off the Companies list, until someone accepts it', async ({ page, crm }) => {
    const company = await crm.createCompany({ status: 'AI_FOUND', leadSource: 'AI_RESEARCH', notes: FOUND_NOTES });
    const person = await crm.createContact(company.id, { title: 'CTO' });

    await page.goto('/companies');
    await expect(page.getByText('waiting for review on')).toBeVisible();
    await expect(page.getByRole('link', { name: company.name, exact: true })).toHaveCount(0);

    await page.getByRole('link', { name: 'AI Found', exact: true }).first().click();
    const card = page.getByRole('article').filter({ hasText: company.name });
    await expect(card).toContainText('Why now: Hiring two forward deployed engineers for a new bank rollout.');
    await expect(card).toContainText(person.name);
    await expect(card.getByRole('link', { name: 'jobs.example' })).toHaveAttribute('href', 'https://jobs.example/northwind-fde');

    await card.getByRole('button', { name: 'Accept' }).click();
    await expect(page.getByRole('status')).toContainText(`${company.name} is now a lead`);
    await expect(card).toHaveCount(0);

    const accepted = await crm.getJson(`/api/companies/${company.id}`);
    expect(accepted.status).toBe('LEAD');
  });

  test('dismissing a found company parks it and moves its untouched people to the Trash', async ({ page, crm }) => {
    const company = await crm.createCompany({ status: 'AI_FOUND', leadSource: 'AI_RESEARCH', notes: FOUND_NOTES });
    await crm.createContact(company.id, { title: 'Head of Talent' });

    await page.goto('/prospects');
    const card = page.getByRole('article').filter({ hasText: company.name });
    await card.getByRole('button', { name: 'Dismiss' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('radio', { name: 'Not a vendor (a bank, insurer, consultancy or staffing firm)' }).check();
    await dialog.getByRole('button', { name: 'Dismiss' }).click();
    await expect(page.getByRole('status')).toContainText(`${company.name} dismissed`);
    await expect(page.getByRole('status')).toContainText('1 person moved to the Trash');
    await expect(card).toHaveCount(0);

    const dismissed = await crm.getJson(`/api/companies/${company.id}`);
    expect(dismissed.status).toBe('NOT_QUALIFIED');
  });
});
