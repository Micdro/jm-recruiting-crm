import fs from 'node:fs';
import { test, expect } from '../support/fixtures.js';

test.describe('Blind candidate profile', () => {
  test('leaves out who they are, warns if it slips back in, and notes where it went', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate({
      currentCompany: 'Cardwise',
      productionEvidence: 'Owned real-time model serving at Cardwise; led two Sev-1 recoveries.',
    });
    await crm.addToSearch(search.id, candidate.id, {
      stage: 'SHORTLISTED',
      rationale: 'Has run real-time scoring inside a card issuer, which is what this search needs.',
    });

    await page.goto(`/candidates/${candidate.id}`);
    await page.getByRole('button', { name: 'Blind profile' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('For')).toHaveValue(/\d+/);
    const evidence = dialog.getByLabel('What they have owned in production');
    await expect(evidence).toHaveValue('Owned real-time model serving at their current employer; led two Sev-1 recoveries.');
    await expect(dialog.getByLabel('Why we are presenting them')).toHaveValue(/card issuer/);

    await evidence.fill(`${candidate.name} owned real-time model serving.`);
    await expect(dialog.getByText('It still mentions their name.')).toBeVisible();
    await evidence.fill('Owned real-time model serving for card authorizations.');
    await dialog.getByLabel('Current employer, described').fill('a top-10 US card issuer');

    const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: 'Download PDF' }).click()]);
    expect(download.suggestedFilename()).toMatch(/^Candidate profile JM-[0-9A-Z]+\.pdf$/);
    expect(fs.readFileSync(await download.path()).subarray(0, 5).toString()).toBe('%PDF-');
    await expect(page.getByRole('status')).toContainText('downloaded');
    await expect(page.getByText(new RegExp(`Blind profile JM-[0-9A-Z]+ prepared for ${company.name}`))).toBeVisible();
  });
});
