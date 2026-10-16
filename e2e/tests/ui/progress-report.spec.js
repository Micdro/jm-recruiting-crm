import fs from 'node:fs';
import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Search progress report', () => {
  test('sums up the search for the client, drafts the asks and notes the report', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const manager = await crm.createContact(company.id, { name: uniqueName('Dana Reyes') });
    const search = await crm.createSearch(company.id, { hiringManagerId: manager.id });
    const submitted = await crm.createCandidate({ name: uniqueName('Sam Ortiz') });
    const interviewing = await crm.createCandidate({ name: uniqueName('Priya Natarajan') });
    const early = await crm.createCandidate({ name: uniqueName('Ivy Chen') });
    await crm.addToSearch(search.id, submitted.id, { stage: 'SUBMITTED' });
    await crm.addToSearch(search.id, interviewing.id, { stage: 'INTERVIEWING' });
    const entry = await crm.addToSearch(search.id, early.id, { stage: 'SOURCED' });
    const moved = await crm.api.put(`/api/pipeline/${entry.id}`, { data: { stage: 'CONTACTED' } });
    expect(moved.ok(), await moved.text()).toBeTruthy();

    await page.goto(`/searches/${search.id}`);
    await page.getByRole('button', { name: 'Progress report' }).click();
    const dialog = page.getByRole('dialog', { name: `Progress report for ${company.name}` });
    const preview = dialog.getByLabel('What the report shows');
    await expect(preview.locator('.progress-funnel div', { hasText: 'Identified' })).toContainText('3');
    await expect(preview.locator('.progress-funnel div', { hasText: 'Submitted to you' })).toContainText('2');
    await expect(preview.locator('.progress-funnel div', { hasText: 'Interviewed' })).toContainText('1');
    await expect(preview).toContainText('3 new candidates identified');
    await expect(dialog.getByLabel('Prepared for')).toHaveValue(manager.name);
    await expect(dialog.getByLabel('What we need from you')).toHaveValue(
      new RegExp(`^Your feedback on ${submitted.name} by \\w+day, \\w+ \\d+\\.\\nInterview feedback and next steps for ${interviewing.name}\\.$`),
    );

    await dialog.getByLabel('Use references instead of names').check();
    await expect(dialog.getByLabel('What we need from you')).toHaveValue(/^Your feedback on JM-[0-9A-Z]+ by /);
    await expect(preview).not.toContainText(submitted.name);

    const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: 'Download PDF' }).click()]);
    expect(download.suggestedFilename()).toMatch(/^Search progress .+\.pdf$/);
    expect(fs.readFileSync(await download.path()).subarray(0, 5).toString()).toBe('%PDF-');
    await expect(page.getByRole('status')).toContainText('Progress report downloaded');

    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByText(`Progress report prepared for ${manager.name}.`)).toBeVisible();
  });
});
