import { test, expect, isoDate, uniqueName } from '../support/fixtures.js';

test.describe('Off-limits', () => {
  test('warns about candidates who work at a client and leaves them out of matches', async ({ page, crm }) => {
    // Opening a search makes the company a client.
    const client = await crm.createCompany({ name: uniqueName('Ledgerlight AI') });
    await crm.createSearch(client.id);
    const insider = await crm.createCandidate({
      name: uniqueName('Kai Moreno'),
      status: 'ACTIVE',
      currentCompany: `${client.name}, Inc.`,
    });
    const other = await crm.createCompany();
    const search = await crm.createSearch(other.id);

    await test.step('the candidate page warns', async () => {
      await page.goto(`/candidates/${insider.id}`);
      await expect(page.getByRole('note')).toContainText(`Off-limits: Kai works at ${client.name}, a client.`);
    });

    await test.step('the company lists who works there', async () => {
      await page.goto(`/companies/${client.id}`);
      await expect(page.locator('.page-sub').getByText('Off-limits', { exact: true })).toBeVisible();
      const here = page.getByRole('region', { name: 'Candidates who work here' });
      await expect(here).toContainText('Its people are off-limits while you work for');
      await expect(here.getByRole('link', { name: insider.name })).toBeVisible();
    });

    await test.step('matches leave them out and say so', async () => {
      await page.goto(`/searches/${search.id}`);
      await page.getByRole('tab', { name: 'Matches' }).click();
      const panel = page.getByRole('region', { name: 'Candidates who fit' });
      await expect(panel.getByText(/Left out because they work at off-limits companies/)).toContainText(insider.name);
      await expect(panel.getByRole('listitem').filter({ has: page.getByRole('link', { name: insider.name }) })).toHaveCount(0);
    });

    await test.step('adding them to a search asks for a second look', async () => {
      await page.getByRole('tab', { name: 'Pipeline' }).click();
      await page.getByLabel('Add a candidate').selectOption(String(insider.id));
      await expect(page.locator('.add-to-search').getByRole('note')).toContainText(`Kai works at ${client.name}, a client`);
      await page.getByRole('button', { name: 'Add anyway' }).click();
      await expect(page.getByRole('status')).toContainText(`Added ${insider.name}`);
    });
  });

  test('a date set on the company ends it early', async ({ page, crm }) => {
    const company = await crm.createCompany({ name: uniqueName('Northwind'), status: 'COMPLETE', offLimitsUntil: isoDate(30) });
    const candidate = await crm.createCandidate({ name: uniqueName('Sam Ortiz'), currentCompany: company.name });

    await page.goto(`/candidates/${candidate.id}`);
    await expect(page.getByRole('note')).toContainText(`Sam works at ${company.name}, a recent client.`);

    await page.goto(`/companies/${company.id}`);
    await page.getByRole('button', { name: 'Edit' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Off-limits until').fill(isoDate(0));
    await dialog.getByRole('button', { name: 'Save company' }).click();
    await expect(page.getByRole('status')).toContainText('Company saved');
    const here = page.getByRole('region', { name: 'Candidates who work here' });
    await expect(here.getByRole('link', { name: candidate.name })).toBeVisible();
    await expect(here).not.toContainText('off-limits');

    await page.goto(`/candidates/${candidate.id}`);
    await expect(page.getByRole('heading', { name: candidate.name, level: 1 })).toBeVisible();
    await expect(page.getByRole('note')).toHaveCount(0);
  });

  test('the candidate form warns while typing the employer', async ({ page, crm }) => {
    const client = await crm.createCompany({ name: uniqueName('Quillstone'), status: 'PLACING' });

    await page.goto('/candidates');
    await page.getByRole('button', { name: 'Add candidate' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Candidate name').fill('Lea Novak');
    await dialog.getByLabel('Current company').fill(client.name.toLowerCase());
    await expect(dialog.getByRole('note')).toContainText(`Off-limits: Lea works at ${client.name}, a client.`);
    await dialog.getByLabel('Current company').fill('Somewhere Else');
    await expect(dialog.getByRole('note')).toHaveCount(0);
  });
});
