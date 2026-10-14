import { test, expect, uniqueName } from '../support/fixtures.js';

test.describe('Candidate matches', () => {
  test('suggests candidates who fit a search and adds one to it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id, {
      location: 'New York, NY',
      workModel: 'HYBRID',
      baseMax: 240000,
      technicalScope: 'Stand up quokkascoring for card fraud inside bank controls',
    });
    const fit = await crm.createCandidate({
      name: uniqueName('Priya Natarajan'),
      status: 'ACTIVE',
      location: 'New York, NY',
      regulatedExperience: true,
      compExpectation: '$230k base',
      productionEvidence: 'Owned quokkascoring for card authorizations at a large issuer.',
    });
    const notOpen = await crm.createCandidate({ name: uniqueName('Not Looking'), status: 'NOT_LOOKING' });

    await page.goto(`/searches/${search.id}`);
    await page.getByRole('tab', { name: 'Matches' }).click();
    const panel = page.getByRole('region', { name: 'Candidates who fit' });
    const match = panel.getByRole('listitem').filter({ has: page.getByRole('link', { name: fit.name }) });
    await expect(match).toContainText('Strong match');
    await expect(match).toContainText('Same track');
    await expect(match).toContainText('Based in New York, NY');
    await expect(match).toContainText('Asks $230k, within the range');
    await expect(match).toContainText('quokkascoring');
    await expect(panel.getByRole('link', { name: notOpen.name })).toHaveCount(0);

    await match.getByRole('button', { name: 'Add to search' }).click();
    await expect(page.getByRole('status')).toContainText(`Added ${fit.name} to this search`);
    await expect(panel.getByRole('link', { name: fit.name })).toHaveCount(0);
    const pipeline = await crm.getJson(`/api/searches/${search.id}/pipeline`);
    expect(pipeline.map((entry) => entry.candidateId)).toContain(fit.id);
  });
});
