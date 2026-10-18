import { test, expect } from '../support/fixtures.js';

test.describe('Monday brief', () => {
  test.beforeEach(async ({ page }) => {
    // Hiding the brief is remembered in the browser; start each test with it showing.
    await page.addInitScript(() => localStorage.removeItem('jmcrm.brief.hidden'));
  });

  test('shows the week’s facts on Today without an AI key, and hides for the week', async ({ page }) => {
    await page.goto('/');
    const brief = page.getByRole('region', { name: 'Monday brief' });
    await expect(brief).toContainText(/The written brief needs an? .+ API key/);
    await expect(brief).toContainText('touches last week');
    await expect(brief.getByRole('heading', { name: 'Waiting too long' })).toBeVisible();
    await expect(brief.getByRole('heading', { name: 'Searches that need attention' })).toBeVisible();

    await brief.getByRole('button', { name: 'Hide this week' }).click();
    await expect(brief.getByRole('button', { name: 'Show', exact: true })).toBeVisible();
    await expect(brief).not.toContainText('touches last week');
  });

  test('writes the brief with AI when Today opens, and rewrites it', async ({ page, crm }) => {
    const real = await crm.getJson('/api/brief');
    const written = (headline) => ({
      headline,
      lastWeek: ['315 touches against a target of 450.'],
      watch: ['Two debriefs are still owed. Log them by Wednesday.'],
      focus: ['Michael: get feedback on the submitted candidate by Tuesday.', 'Jane: book three calls for the platform search.'],
      writtenAt: new Date().toISOString(),
      writtenBy: 'E2E',
      provider: 'Claude',
      costCents: 1,
    });
    const writes = [];
    await page.route('**/api/brief', (route) =>
      route.fulfill({ json: { ...real, written: null, canWrite: true, provider: 'Claude' } }),
    );
    await page.route('**/api/brief/write*', (route) => {
      const rewrite = new URL(route.request().url()).searchParams.get('rewrite') === 'true';
      writes.push(rewrite);
      route.fulfill({
        json: { ...real, canWrite: true, provider: 'Claude', written: written(rewrite ? 'Rewritten for today.' : 'Outreach ran under target last week.') },
      });
    });

    await page.goto('/');
    const brief = page.getByRole('region', { name: 'Monday brief' });
    await expect(brief).toContainText('Outreach ran under target last week.');
    await expect(brief.getByRole('listitem').filter({ hasText: 'Jane: book three calls' })).toBeVisible();
    await expect(brief).toContainText('Written by Claude for $0.01');
    // The facts stay folded away until asked for.
    await expect(brief.getByRole('heading', { name: 'Waiting too long' })).toHaveCount(0);
    await brief.getByRole('button', { name: 'Show the details' }).click();
    await expect(brief.getByRole('heading', { name: 'Waiting too long' })).toBeVisible();

    await brief.getByRole('button', { name: 'Rewrite' }).click();
    await expect(brief).toContainText('Rewritten for today.');
    expect(writes).toContain(true);
  });
});
