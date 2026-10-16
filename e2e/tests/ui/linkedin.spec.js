import { test, expect, uniqueName } from '../support/fixtures.js';

const profile = ({ name, headline, company }) => `<!doctype html><title>${name} | LinkedIn</title><body><main>
  <h1>${name}</h1>
  <div class="text-body-medium break-words">${headline}</div>
  <span class="text-body-small inline t-black--light break-words">New York, New York, United States</span>
  ${company ? `<button aria-label="Current company: ${company}. Click to skip to experience card">${company}</button>` : ''}
</main></body>`;

/** Opens a stand-in LinkedIn profile, clicks the bookmark on it, and returns the CRM tab it opens. */
async function addFromProfile(page, context, slug, person) {
  await page.goto('/linkedin');
  const href = await page.getByRole('link', { name: 'Add to JM CRM' }).getAttribute('href');
  expect(href.startsWith('javascript:')).toBeTruthy();
  await context.route(`https://www.linkedin.com/in/${slug}/`, (route) =>
    route.fulfill({ contentType: 'text/html', body: profile(person) }));
  const linkedIn = await context.newPage();
  await linkedIn.goto(`https://www.linkedin.com/in/${slug}/`);
  const [crm] = await Promise.all([
    context.waitForEvent('page'),
    linkedIn.evaluate(decodeURIComponent(href.slice('javascript:'.length))),
  ]);
  await expect(crm.getByRole('heading', { name: 'Add as' })).toBeVisible();
  return crm;
}

test.describe('Add from LinkedIn', () => {
  test('saves a hiring manager as a contact, adding their company', async ({ page, context, crm }) => {
    const name = uniqueName('Maya Chen');
    const company = uniqueName('Northbeam Risk');
    crm.trackName('contacts', name);
    crm.trackName('companies', company);

    const tab = await addFromProfile(page, context, 'maya-chen-e2e', { name, headline: `Head of ML Platform at ${company}`, company });
    await expect(tab.getByLabel('Contact name')).toHaveValue(name);
    await expect(tab.getByLabel('Title')).toHaveValue('Head of ML Platform');
    await expect(tab.getByLabel('LinkedIn profile')).toHaveValue('https://www.linkedin.com/in/maya-chen-e2e/');

    await tab.getByRole('button', { name: `Add ${company} as a company` }).click();
    await expect(tab.getByLabel('Company')).toHaveValue(/\d+/);
    await tab.getByRole('button', { name: 'Save contact' }).click();
    await expect(tab).toHaveURL(/\/contacts\/\d+$/);
    await expect(tab.getByRole('heading', { name, level: 1 })).toBeVisible();

    // Clicking the button on the same profile again points to the record that's already there.
    const again = await addFromProfile(page, context, 'maya-chen-e2e', { name, headline: 'Head of ML Platform', company });
    await expect(again.getByText('Already in the CRM')).toBeVisible();
  });

  test('saves an engineer as a candidate', async ({ page, context, crm }) => {
    const name = uniqueName('Arjun Mehta');
    crm.trackName('candidates', name);

    const tab = await addFromProfile(page, context, 'arjun-mehta-e2e', {
      name,
      headline: 'Staff ML Engineer @ Cardwise | ex-Stripe',
      company: '',
    });
    await tab.locator('label.segment', { hasText: 'Candidate' }).click();
    await expect(tab.getByLabel('Current title')).toHaveValue('Staff ML Engineer');
    await expect(tab.getByLabel('Current company')).toHaveValue('Cardwise');
    await tab.getByRole('button', { name: 'Save candidate' }).click();
    await expect(tab).toHaveURL(/\/candidates\/\d+$/);
    const id = tab.url().split('/').pop();
    const saved = await crm.getJson(`/api/candidates/${id}`);
    expect(saved).toMatchObject({ name, currentTitle: 'Staff ML Engineer', currentCompany: 'Cardwise', source: 'LINKEDIN' });
  });

  test('spots a new job for someone already in the CRM and updates their record', async ({ page, crm }) => {
    const url = `https://www.linkedin.com/in/job-change-${Date.now()}/`;
    const candidate = await crm.createCandidate({
      name: uniqueName('Lea Novak'),
      currentTitle: 'Staff ML Engineer',
      currentCompany: 'Cardwise',
      linkedinUrl: url,
      notes: 'Prefers hybrid roles.',
    });
    const newEmployer = uniqueName('Harbor Trust');
    const open = (headline, company) =>
      page.goto(`/linkedin?${new URLSearchParams({ name: candidate.name, headline, company, url })}`);

    // The same job, written differently, isn't a change.
    await open('Staff ML Engineer at Cardwise', 'Cardwise, Inc.');
    await expect(page.getByText('Already in the CRM')).toBeVisible();
    await expect(page.getByText('New job.')).toHaveCount(0);

    await open(`Head of ML Platform at ${newEmployer} | ex-Cardwise`, newEmployer);
    await expect(page.locator('.linkedin-existing')).toContainText(
      `LinkedIn shows Head of ML Platform at ${newEmployer}. The candidate record has Staff ML Engineer at Cardwise.`,
    );
    await page.getByRole('button', { name: 'Update candidate' }).click();
    const update = page.getByRole('region', { name: `Update ${candidate.name}` });
    await expect(update.getByLabel('Current title')).toHaveValue('Head of ML Platform');
    await expect(update.getByLabel('Current company')).toHaveValue(newEmployer);
    await update.getByRole('button', { name: 'Save candidate' }).click();

    await expect(page).toHaveURL(new RegExp(`/candidates/${candidate.id}$`));
    await expect(page.getByText(`Job change from LinkedIn: Head of ML Platform at ${newEmployer} (was Staff ML Engineer at Cardwise).`)).toBeVisible();
    const saved = await crm.getJson(`/api/candidates/${candidate.id}`);
    expect(saved).toMatchObject({ currentTitle: 'Head of ML Platform', currentCompany: newEmployer, notes: 'Prefers hybrid roles.' });
  });
});
