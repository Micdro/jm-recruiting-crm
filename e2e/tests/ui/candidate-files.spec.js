import { test, expect } from '../support/fixtures.js';

test.describe('Candidate files', () => {
  test('adds a resume, opens it, downloads it and deletes it', async ({ page, crm }) => {
    const candidate = await crm.createCandidate();
    const resume = Buffer.from('%PDF-1.4\n% Resume for the files test\n');

    await page.goto(`/candidates/${candidate.id}`);
    const panel = page.getByRole('region', { name: 'Resume and files' });
    await expect(panel.getByText('No files yet')).toBeVisible();

    await panel.locator('input[type="file"]').setInputFiles({ name: 'Resume.pdf', mimeType: 'application/pdf', buffer: resume });
    await expect(page.getByRole('status')).toContainText('Added Resume.pdf');
    const link = panel.getByRole('link', { name: 'Resume.pdf' });
    await expect(link).toBeVisible();
    await expect(panel.getByText(/PDF, added/)).toBeVisible();

    // Opening it serves the same file back.
    const opened = await crm.api.get(await link.getAttribute('href'));
    expect(opened.headers()['content-type']).toBe('application/pdf');
    expect(Buffer.compare(await opened.body(), resume)).toBe(0);

    const [download] = await Promise.all([page.waitForEvent('download'), panel.getByRole('link', { name: 'Download' }).click()]);
    expect(download.suggestedFilename()).toBe('Resume.pdf');

    // Something that isn't a resume is turned away.
    await panel.locator('input[type="file"]').setInputFiles({ name: 'notes.pdf', mimeType: 'application/pdf', buffer: Buffer.from('not a pdf') });
    await expect(panel.getByText("That file isn't a real .pdf file")).toBeVisible();

    await panel.getByRole('button', { name: 'Delete' }).click();
    await panel.getByRole('button', { name: 'Confirm delete' }).click();
    await expect(panel.getByText('No files yet')).toBeVisible();
  });
});
