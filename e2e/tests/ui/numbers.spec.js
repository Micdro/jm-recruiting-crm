import { test, expect } from '../support/fixtures.js';

test.describe('Weekly numbers', () => {
  test('counts a touch and its reply, and shows them by week, touch and person', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);
    const before = await crm.getJson('/api/reports/weekly');

    for (const type of ['LINKEDIN', 'REPLY']) {
      const response = await crm.api.post(`/api/contacts/${contact.id}/activities`, { data: { type } });
      expect(response.ok(), await response.text()).toBeTruthy();
    }

    // Other tests log touches at the same time, so check that ours were added rather than exact totals.
    const after = await crm.getJson('/api/reports/weekly');
    const thisWeek = (report) => report.weeks[report.weeks.length - 1];
    expect(thisWeek(after).linkedin).toBeGreaterThan(thisWeek(before).linkedin);
    expect(thisWeek(after).replies).toBeGreaterThan(thisWeek(before).replies);
    expect(after.byTouch[0].replies).toBeGreaterThan(before.byTouch[0].replies);

    await page.goto('/');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Numbers' }).click();
    await expect(page.getByRole('heading', { name: 'Numbers', level: 1 })).toBeVisible();
    await expect(page.getByRole('img', { name: /Touches per week by channel/ })).toBeVisible();
    await expect(page.getByText('Touches this week')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Which touch gets replies' })).toBeVisible();
    await expect(page.getByRole('row', { name: /so far/ })).toBeVisible();

    await page.locator('label.segment', { hasText: 'Team' }).click();
    const people = page.getByRole('region', { name: 'Scoreboard this week' });
    await expect(people).toBeVisible();
    await expect(people.getByRole('row')).not.toHaveCount(1);
    await expect(people.getByRole('columnheader', { name: 'Streak' })).toBeVisible();
  });
});
