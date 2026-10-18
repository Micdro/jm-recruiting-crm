import { AI_READY, test, expect, uniqueName } from '../support/fixtures.js';

// The AI itself is stood in for in the browser: these tests check what the CRM does with its answers.
const JD = 'About the role\nShip claims models into carriers.\n\nWhat you’ll own\nEach deployment end to end.';

test.describe('AI for searches', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/ai', (route) => route.fulfill({ json: AI_READY }));
  });

  test('opens a search from intake notes, with its brief and job description', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const title = uniqueName('Forward Deployed Engineer');
    await page.route('**/api/searches/read-intake', (route) =>
      route.fulfill({
        json: {
          search: {
            companyId: company.id,
            hiringManagerId: null,
            title,
            track: 'PRODUCTION_AI_ENGINEERING',
            status: null,
            engagementType: 'EXCLUSIVE',
            feePercent: 22,
            baseMin: 180000,
            baseMax: 230000,
            location: 'New York, NY',
            workModel: 'HYBRID',
            timeline: 'ASAP',
            openedDate: null,
            productionOutcome: 'Claims triage models live at two carriers.',
            technicalScope: 'Python, Kubernetes, customer VPCs.',
            accountability: 'Owns each deployment end to end.',
            regulatoryConstraints: 'Model risk review before go-live.',
            notes: '- Two rounds, then the CTO.',
            jobDescription: JD,
            ownerId: null,
          },
          companyHeard: company.name,
          companies: [],
          hiringManager: 'Dana Reyes',
          provider: 'Claude',
          costCents: 2,
        },
      }),
    );

    await page.goto('/searches');
    await page.getByRole('button', { name: 'From intake notes' }).click();
    const dialog = page.getByRole('dialog', { name: 'Open a search from intake notes' });
    await dialog.getByLabel('Notes from the intake call').fill('FDE, hybrid NYC, 180-230, ASAP, exclusive at 22%.');
    await dialog.getByRole('button', { name: 'Read notes' }).click();

    await expect(dialog.getByText('Read by Claude for $0.02')).toBeVisible();
    await expect(dialog.getByText('The notes name Dana Reyes as the hiring manager')).toBeVisible();
    await expect(dialog.getByLabel('Role title')).toHaveValue(title);
    await expect(dialog.getByLabel('Company', { exact: true })).toHaveValue(String(company.id));
    await expect(dialog.getByLabel('Base salary from')).toHaveValue('180000');
    await expect(dialog.getByLabel('Job description')).toHaveValue(JD);
    await dialog.getByRole('button', { name: 'Open search' }).click();

    await expect(page.getByRole('status')).toContainText(`Opened ${title}`);
    await expect(page).toHaveURL(/\/searches\/\d+$/);
    const id = Number(page.url().split('/').pop());
    crm.track('searches', id);
    const saved = await crm.getJson(`/api/searches/${id}`);
    expect(saved).toMatchObject({
      companyId: company.id,
      engagementType: 'EXCLUSIVE',
      workModel: 'HYBRID',
      timeline: 'ASAP',
      regulatoryConstraints: 'Model risk review before go-live.',
      jobDescription: JD,
    });
  });

  test('writes a job description from the brief and saves it', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    await page.route(`**/api/searches/${search.id}/job-description/draft`, (route) =>
      route.fulfill({ json: { jobDescription: JD, provider: 'Claude', costCents: 1 } }),
    );

    await page.goto(`/searches/${search.id}`);
    await page.getByRole('tab', { name: 'Brief' }).click();
    const panel = page.getByRole('region', { name: 'Job description' });
    await expect(panel).toContainText('No job description yet.');
    await panel.getByRole('button', { name: 'Write with AI' }).click();

    const dialog = page.getByRole('dialog', { name: `Job description: ${search.title}` });
    await expect(dialog.getByText('Written by Claude for $0.01 from the brief')).toBeVisible();
    await dialog.getByLabel('Job description').fill(`${JD}\n\nEdited by hand.`);
    await dialog.getByRole('button', { name: 'Save job description' }).click();

    await expect(page.getByRole('status')).toContainText('Job description saved');
    await expect(panel).toContainText('Edited by hand.');
    await expect(panel.getByRole('button', { name: 'Rewrite with AI' })).toBeVisible();
    const saved = await crm.getJson(`/api/searches/${search.id}`);
    expect(saved.jobDescription).toBe(`${JD}\n\nEdited by hand.`);
    expect(saved.productionOutcome).toBe(search.productionOutcome);
  });

  test('drafts the rationale for a candidate in the search', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const entry = await crm.addToSearch(search.id, candidate.id, { stage: 'ASSESSING' });
    const rationale = `${candidate.name.split(' ')[0]} owned real-time model serving for a card issuer.`;
    await page.route(`**/api/pipeline/${entry.id}/rationale/draft`, (route) =>
      route.fulfill({ json: { rationale, provider: 'Claude', costCents: 1 } }),
    );

    await page.goto(`/searches/${search.id}`);
    await page.getByTestId('pipeline-card').filter({ hasText: candidate.name }).getByRole('button', { name: 'Add rationale' }).click();
    const dialog = page.getByRole('dialog', { name: `Why ${candidate.name}?` });
    await dialog.getByRole('button', { name: 'Draft with AI' }).click();
    await expect(dialog.getByLabel('Rationale')).toHaveValue(rationale);
    await expect(dialog.getByText('Drafted by Claude for $0.01')).toBeVisible();
    await dialog.getByRole('button', { name: 'Save rationale' }).click();

    await expect.poll(async () => (await crm.getJson(`/api/searches/${search.id}/pipeline`))[0].rationale).toBe(rationale);
  });
});
