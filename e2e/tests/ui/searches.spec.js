import { test, expect } from '../support/fixtures.js';

test.describe('Searches', () => {
  test('moves a candidate onto the shortlist with a rationale', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id, { status: 'ACTIVE' });
    const candidate = await crm.createCandidate();

    await page.goto(`/searches/${search.id}`);
    await expect(page.getByRole('heading', { name: search.title, level: 1 })).toBeVisible();

    await test.step('add the candidate', async () => {
      await page.getByLabel('Add a candidate').selectOption({ value: String(candidate.id) });
      await page.getByRole('button', { name: 'Add to search' }).click();
      await expect(page.getByTestId('pipeline-card').filter({ hasText: candidate.name })).toBeVisible();
    });

    await test.step('shortlisting asks for the rationale first', async () => {
      const card = page.getByTestId('pipeline-card').filter({ hasText: candidate.name });
      await card.getByLabel(`Stage for ${candidate.name}`).selectOption('SHORTLISTED');

      const dialog = page.getByRole('dialog', { name: `Why ${candidate.name}?` });
      await dialog.getByLabel('Rationale').fill('Owned model serving at a card issuer; maps to the fraud brief.');
      await dialog.getByRole('button', { name: 'Save and move to Shortlisted' }).click();

      const shortlisted = page.getByRole('region', { name: 'Shortlisted, 1' });
      await expect(shortlisted.getByText(candidate.name)).toBeVisible();
    });

    await test.step('the shortlist shows the rationale and evidence', async () => {
      await page.getByRole('tab', { name: 'Shortlist' }).click();
      await expect(page.getByRole('heading', { name: candidate.name, level: 3 })).toBeVisible();
      await expect(page.getByText('Owned model serving at a card issuer; maps to the fraud brief.')).toBeVisible();
      await expect(page.getByText(candidate.productionEvidence)).toBeVisible();
    });

    const pipeline = await crm.getJson(`/api/searches/${search.id}/pipeline`);
    expect(pipeline[0]).toMatchObject({ stage: 'SHORTLISTED', candidateId: candidate.id });
  });

  test('opens a search from a company page', async ({ page, crm }) => {
    const company = await crm.createCompany();

    await page.goto(`/companies/${company.id}`);
    await page.getByRole('button', { name: 'Open search' }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Role title').fill('AI Platform Engineer');
    await dialog.getByLabel('Fee (% of first-year base)').fill('22');
    await dialog.getByLabel('Production outcome').fill('LLM gateway in production for two insurers');
    await dialog.getByRole('button', { name: 'Open search' }).click();

    await expect(page.getByRole('heading', { name: 'AI Platform Engineer', level: 1 })).toBeVisible();
    await expect(page.getByText('Contingency, 22% fee')).toBeVisible();

    const searches = await crm.getJson(`/api/searches?companyId=${company.id}`);
    searches.forEach((search) => crm.track('searches', search.id));
    expect(searches[0]).toMatchObject({ title: 'AI Platform Engineer', status: 'INTAKE' });
  });
});
