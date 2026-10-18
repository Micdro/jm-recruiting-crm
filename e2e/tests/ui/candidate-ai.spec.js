import { test, expect, isoDate, uniqueEmail, uniqueName } from '../support/fixtures.js';

// The AI itself is stood in for in the browser: these tests check what the CRM does with its answers.
const AI_READY = {
  provider: 'CLAUDE',
  providers: [
    { id: 'CLAUDE', label: 'Claude', hasKey: true, keyHint: '…1234', model: 'claude-sonnet-5-5', models: [] },
    { id: 'OPENAI', label: 'OpenAI', hasKey: false, keyHint: null, model: 'gpt-5.5', models: [] },
  ],
  dailyLimitDollars: 5,
  monthlyLimitDollars: 50,
  spentTodayCents: 0,
  spentThisMonthCents: 0,
  canManage: false,
  owner: 'Michael',
};

const NO_CHANGE = {
  status: null,
  currentTitle: null,
  currentCompany: null,
  location: null,
  workPreference: null,
  willingToRelocate: null,
  employmentType: null,
  compExpectation: null,
  addEvidence: null,
  searchId: null,
};

test.describe('AI for candidates', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/ai', (route) => route.fulfill({ json: AI_READY }));
  });

  test('adds a candidate from a resume, after checking what AI read', async ({ page, crm }) => {
    const existing = await crm.createCandidate();
    const name = uniqueName('Ada Lovelace');
    const email = uniqueEmail('ada');
    crm.trackName('candidates', name);
    await page.route('**/api/candidates/read-resume*', (route) =>
      route.fulfill({
        json: {
          candidate: {
            name,
            email,
            phone: '+1 512 555 0100',
            linkedinUrl: 'https://www.linkedin.com/in/ada-example',
            location: 'Austin, TX',
            currentTitle: 'Staff ML Engineer',
            currentCompany: 'Example Bank',
            track: 'PRODUCTION_AI_ENGINEERING',
            regulatedExperience: true,
            roleAreas: ['DEPLOY', 'OPERATE'],
            experience: 'YEARS_8_9',
            productionEvidence: '- Owned fraud scoring, 40M decisions a day',
          },
          matches: [{ id: existing.id, name: existing.name, why: 'same LinkedIn' }],
          fileName: 'ada.txt',
          provider: 'Claude',
          costCents: 1,
        },
      }),
    );

    await page.goto('/candidates');
    await page.getByRole('button', { name: 'Add from resume' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add from resume' });
    await expect(dialog.getByText('goes to Claude to read')).toBeVisible();
    await dialog.locator('input[type="file"]').setInputFiles({
      name: 'ada.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Ada Lovelace\nStaff ML Engineer, Example Bank\nOwned fraud scoring in production.\n'),
    });

    await expect(dialog.getByText('Read by Claude for $0.01 from ada.txt')).toBeVisible();
    await expect(dialog.getByRole('link', { name: existing.name })).toBeVisible();
    await expect(dialog.getByLabel('Candidate name')).toHaveValue(name);
    await expect(dialog.getByLabel('Current title')).toHaveValue('Staff ML Engineer');
    await expect(dialog.getByLabel('Experience', { exact: true })).toHaveValue('YEARS_8_9');
    await expect(dialog.getByLabel('Production evidence')).toHaveValue('- Owned fraud scoring, 40M decisions a day');
    await dialog.getByLabel('Source', { exact: true }).selectOption({ label: 'Inbound' });
    await dialog.getByRole('button', { name: 'Save candidate' }).click();

    await expect(page.getByRole('status')).toContainText(`Added ${name} with their resume`);
    await expect(page).toHaveURL(/\/candidates\/\d+$/);
    const id = Number(page.url().split('/').pop());
    crm.track('candidates', id);
    await expect(page.getByRole('region', { name: 'Resume and files' }).getByRole('link', { name: 'ada.txt' })).toBeVisible();

    const saved = await crm.getJson(`/api/candidates/${id}`);
    expect(saved).toMatchObject({
      email,
      source: 'INBOUND',
      status: 'ACTIVE',
      experience: 'YEARS_8_9',
      regulatedExperience: true,
      roleAreas: ['DEPLOY', 'OPERATE'],
    });
  });

  test('fills in what the record is missing from a resume already on it', async ({ page, crm }) => {
    const candidate = await crm.createCandidate({
      currentTitle: null,
      currentCompany: null,
      location: 'Denver, CO',
      track: null,
      regulatedExperience: false,
      productionEvidence: null,
    });
    const upload = await crm.api.post(`/api/candidates/${candidate.id}/files?name=resume.txt`, {
      data: Buffer.from('A resume with enough words in it to be read by the AI.\n'),
      headers: { 'Content-Type': 'application/octet-stream' },
    });
    expect(upload.ok()).toBeTruthy();
    await page.route('**/api/candidates/*/files/*/read', (route) =>
      route.fulfill({
        json: {
          candidate: {
            name: candidate.name,
            location: 'Austin, TX',
            currentTitle: 'Staff ML Engineer',
            currentCompany: 'Example Bank',
            roleAreas: [],
            regulatedExperience: false,
          },
          matches: [],
          fileName: 'resume.txt',
          provider: 'Claude',
          costCents: 1,
        },
      }),
    );

    await page.goto(`/candidates/${candidate.id}`);
    await page.getByRole('region', { name: 'Resume and files' }).getByRole('button', { name: 'Fill in from this' }).click();
    const dialog = page.getByRole('dialog', { name: `Edit ${candidate.name}` });
    await expect(dialog.getByText('Filled in: Current title, Current company.')).toBeVisible();
    // What the record already had stays.
    await expect(dialog.getByLabel('Location', { exact: true })).toHaveValue('Denver, CO');
    await expect(dialog.getByLabel('Current title')).toHaveValue('Staff ML Engineer');
    await dialog.getByRole('button', { name: 'Save candidate' }).click();
    await expect(page.getByRole('status')).toContainText('Candidate saved');

    const saved = await crm.getJson(`/api/candidates/${candidate.id}`);
    expect(saved).toMatchObject({ location: 'Denver, CO', currentTitle: 'Staff ML Engineer', currentCompany: 'Example Bank' });
  });

  test('writes up a call from notes and saves what was left ticked', async ({ page, crm }) => {
    const candidate = await crm.createCandidate({ status: 'ACTIVE', compExpectation: null });
    const firstName = candidate.name.split(' ')[0];
    const nextDate = isoDate(2);
    await page.route(`**/api/candidates/${candidate.id}/call-notes/read`, (route) =>
      route.fulfill({
        json: {
          update: {
            ...NO_CHANGE,
            type: 'CALL',
            summary: '- Wants a staff role, remote only.\n- Interviewing at two banks.\nNext: Send the job description',
            status: 'PASSIVE',
            workPreference: 'REMOTE',
            compExpectation: '$240k base plus equity',
            addEvidence: '- Led the LLM eval rollout',
            nextActionDate: nextDate,
            nextActionType: 'EMAIL',
          },
          nextStep: 'Send the job description',
          provider: 'Claude',
          costCents: 1,
        },
      }),
    );

    await page.goto(`/candidates/${candidate.id}`);
    await page.getByRole('button', { name: 'Notes from a call' }).click();
    const dialog = page.getByRole('dialog', { name: `Notes from a call with ${candidate.name}` });
    await dialog.getByLabel('Your notes').fill('remote only. 240 + eq. 2 banks. send JD by fri');
    await dialog.getByRole('button', { name: 'Read notes' }).click();

    await expect(dialog.getByText('Read by Claude for $0.01')).toBeVisible();
    await expect(dialog.getByLabel(`For ${firstName}’s timeline`)).toHaveValue(/Wants a staff role, remote only/);
    await expect(dialog.getByRole('checkbox', { name: /Status: Active to Passive/ })).toBeChecked();
    await expect(dialog.getByLabel('Next action date')).toHaveValue(nextDate);
    await dialog.getByRole('checkbox', { name: /Compensation/ }).uncheck();
    await dialog.getByRole('button', { name: 'Save call' }).click();

    await expect(page.getByRole('status')).toContainText(`Saved the call with ${firstName}`);
    await expect(page.getByRole('region', { name: 'History' }).getByText('Interviewing at two banks')).toBeVisible();

    const saved = await crm.getJson(`/api/candidates/${candidate.id}`);
    expect(saved).toMatchObject({
      status: 'PASSIVE',
      workPreference: 'REMOTE',
      compExpectation: null,
      nextActionDate: nextDate,
      nextActionType: 'EMAIL',
    });
    expect(saved.productionEvidence).toContain('- Led the LLM eval rollout');
  });
});
