import { test, expect, daysFrom } from '../support/fixtures.js';

test.describe('Interviews', () => {
  test('schedules an interview from the search, then debriefs both sides from Today', async ({ page, crm }) => {
    const { today } = await crm.getJson('/api/dashboard');
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const entry = await crm.addToSearch(search.id, candidate.id, { stage: 'SUBMITTED', rationale: 'Ran production ML at a bank' });

    await page.goto(`/searches/${search.id}`);
    await page.getByRole('button', { name: `Schedule an interview for ${candidate.name}` }).click();
    const form = page.getByRole('dialog');
    await form.getByLabel('Date').fill(daysFrom(today, -1));
    await form.getByLabel('Time').fill('10:00');
    await form.getByLabel('What it is').fill('Round 1');
    await form.getByRole('button', { name: 'Schedule interview' }).click();

    await expect(page.locator('.toast-region')).toContainText(`Interview set for ${candidate.name}`);
    const card = page.getByTestId('pipeline-card').filter({ hasText: candidate.name });
    await expect(card).toContainText('10:00 AM');
    await expect(card).toContainText('Round 1');
    await expect(page.getByRole('region', { name: /^Interviewing/ })).toContainText(candidate.name);

    await page.goto('/');
    const row = page.getByTestId('queue-interview').filter({ hasText: candidate.name });
    await expect(row).toContainText('Interviewed');

    await row.getByRole('button', { name: `Debrief ${candidate.name}` }).click();
    await page.getByRole('dialog').getByLabel('Notes').fill('Keen, wants a decision within two weeks');
    await page.getByRole('dialog').getByRole('button', { name: 'Save debrief' }).click();
    await expect(row).toContainText('Candidate debriefed');

    await row.getByRole('button', { name: `Debrief ${company.name} about ${candidate.name}` }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Save debrief' }).click();
    await expect(row).toHaveCount(0);

    const saved = (await crm.getJson(`/api/searches/${search.id}/pipeline`)).find((row) => row.id === entry.id);
    expect(saved).toMatchObject({ stage: 'INTERVIEWING', candidateDebriefed: true, clientDebriefed: true, debriefDue: false });
    const history = await crm.getJson(`/api/activities?candidateId=${candidate.id}`);
    expect(history.map((activity) => activity.summary)).toContainEqual(
      expect.stringContaining('Keen, wants a decision within two weeks'),
    );
  });
});
