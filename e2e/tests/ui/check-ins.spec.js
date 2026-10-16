import { test, expect, daysFrom } from '../support/fixtures.js';

test.describe('Guarantee check-ins', () => {
  test('checks in on a placed candidate from Today', async ({ page, crm }) => {
    const { today } = await crm.getJson('/api/dashboard');
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const placement = await crm.createPlacement({
      searchId: search.id,
      candidateId: candidate.id,
      startDate: daysFrom(today, -31),
      baseSalary: 200000,
    });

    await page.goto('/');
    const row = page.getByTestId('queue-check-in').filter({ hasText: candidate.name });
    await expect(row).toContainText('30-day check-in');

    await row.getByRole('button', { name: `Check in on ${candidate.name}` }).click();
    await page.getByRole('dialog').getByLabel('Notes').fill('Settled in; manager happy with the first deployment');
    await page.getByRole('dialog').getByRole('button', { name: 'Save check-in' }).click();

    await expect(page.locator('.toast-region')).toContainText('Check-in saved');
    await expect(row).toHaveCount(0);
    expect((await crm.getJson(`/api/placements/${placement.id}`)).checkedInOn).toBe(today);

    await page.goto('/placements');
    await expect(page.getByRole('row').filter({ hasText: candidate.name })).toContainText('60-day check-in');
  });
});
