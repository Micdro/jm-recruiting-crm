import { test, expect, isoDate, uniqueName } from '../support/fixtures.js';

test.describe('Marketing', () => {
  test('plans a post for today, marks it posted and keeps its results', async ({ page, crm }) => {
    const title = uniqueName('Why banks hire FDEs first');

    await page.goto('/marketing');
    await page.getByRole('button', { name: 'Add idea' }).click();
    const add = page.getByRole('dialog', { name: 'Add a post' });
    await add.getByLabel('Title').fill(title);
    await add.getByLabel('Day to post').fill(isoDate(0));
    await add.getByLabel('Theme').selectOption({ label: 'FDE hiring' });
    await add.getByLabel('Notes').fill('Banks buy outcomes before platforms.');
    await add.getByLabel('Post', { exact: true }).fill('Banks buy outcomes first.');
    await add.getByRole('button', { name: 'Add post' }).click();
    await expect(page.getByRole('status')).toContainText(`Added ${title}`);

    const saved = (await crm.getJson('/api/marketing/posts')).find((post) => post.title === title);
    crm.track('posts', saved.id);
    // Words in it make an idea a draft.
    expect(saved).toMatchObject({ status: 'DRAFT', theme: 'FDE_HIRING', plannedFor: isoDate(0), account: 'PERSONAL' });

    const week = page.getByRole('region', { name: /Week of/ });
    await week.getByRole('button', { name: title }).click();
    const edit = page.getByRole('dialog', { name: title });
    await edit.getByLabel(/LinkedIn link/).fill('https://www.linkedin.com/posts/e2e-example');
    await edit.getByRole('button', { name: 'Mark as posted' }).click();
    await expect(page.getByRole('status')).toContainText('Marked as posted');

    await edit.getByLabel('Impressions').fill('1200');
    await edit.getByLabel('Conversations').fill('2');
    await edit.getByRole('button', { name: 'Save results' }).click();
    await expect(page.getByRole('status')).toContainText('Results saved');

    const posted = await crm.getJson(`/api/marketing/posts/${saved.id}`);
    expect(posted).toMatchObject({
      status: 'POSTED',
      postedOn: isoDate(0),
      postUrl: 'https://www.linkedin.com/posts/e2e-example',
      impressions: 1200,
      conversations: 2,
    });

    await edit.getByRole('button', { name: 'Close' }).first().click();
    await expect(week.getByRole('button', { name: title })).toContainText('Posted');
    await expect(week.getByRole('button', { name: title })).toContainText('2 conversations');
  });

  test('keeps an idea without a day in the ideas list', async ({ page, crm }) => {
    const title = uniqueName('Three questions before your first FDE');
    const idea = await crm.api.post('/api/marketing/posts', { data: { title } }).then((response) => response.json());
    crm.track('posts', idea.id);

    await page.goto('/marketing');
    const ideas = page.getByRole('region', { name: /Ideas/ });
    await expect(ideas.getByRole('button', { name: title })).toContainText('Idea');

    await page.getByRole('button', { name: 'Next week' }).click();
    await expect(page).toHaveURL(/week=\d{4}-\d{2}-\d{2}/);
    await expect(ideas.getByRole('button', { name: title })).toBeVisible();
  });
});

test.describe('Marketing plan, pictures and posting', () => {
  // A 1x1 PNG.
  const PNG = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
    'base64',
  );

  test('saves the style and a posting day', async ({ page }) => {
    await page.goto('/marketing?view=plan');
    const style = page.getByLabel('How the posts should sound');
    const before = await style.inputValue();
    const marker = `Short sentences. E2E ${Date.now()}`;
    await style.fill(marker);
    await page.getByRole('button', { name: 'Add a posting day' }).click();
    const days = page.getByRole('region', { name: 'Posting days' });
    const last = days.getByLabel('Day', { exact: true }).last();
    await last.selectOption({ label: 'Wednesday' });
    await days.getByLabel('About', { exact: true }).last().selectOption({ label: 'FDE hiring' });
    await page.getByRole('button', { name: 'Save plan' }).click();
    await expect(page.getByRole('status')).toContainText('Plan saved');

    await page.reload();
    await expect(page.getByLabel('How the posts should sound')).toHaveValue(marker);
    await expect(days.getByLabel('Day', { exact: true }).last()).toHaveValue('WEDNESDAY');

    // Put the plan back as it was for other runs.
    await page.getByLabel('How the posts should sound').fill(before);
    await days.getByRole('button', { name: 'Remove Wednesday' }).last().click();
    await page.getByRole('button', { name: 'Save plan' }).click();
    await expect(page.getByRole('status')).toContainText('Plan saved');
  });

  test('shows the redirect URL to add to the firm’s LinkedIn app', async ({ page }) => {
    await page.goto('/marketing?view=plan');
    const panel = page.getByRole('region', { name: 'Posting to LinkedIn' });
    await expect(panel).toBeVisible();
    await expect(panel.locator('code')).toContainText('/api/linkedin/callback');
  });

  test('uploads a picture and shows the post in Upcoming and on Today', async ({ page, crm }) => {
    const title = uniqueName('Platform engineers inside a bank');
    const created = await crm.api
      .post('/api/marketing/posts', { data: { title, body: 'Model risk review is part of the product.', plannedFor: isoDate(0) } })
      .then((response) => response.json());
    crm.track('posts', created.id);

    await page.goto(`/marketing?post=${created.id}`);
    const editor = page.getByRole('dialog', { name: title });
    await editor.getByLabel('Upload picture').setInputFiles({ name: 'picture.png', mimeType: 'image/png', buffer: PNG });
    await expect(editor.getByRole('img', { name: 'The post’s picture' })).toBeVisible();
    const saved = await crm.getJson(`/api/marketing/posts/${created.id}`);
    expect(saved.picture).toMatchObject({ source: 'UPLOAD', ready: true });
    // How it opens in the feed: this one is short enough to show whole.
    await expect(editor.getByLabel('How it opens on LinkedIn')).toContainText('The whole post shows in the feed.');
    // Not connected to LinkedIn here, so it says how to post.
    await expect(editor.getByRole('link', { name: /connect your LinkedIn/ })).toBeVisible();
    await editor.getByRole('button', { name: 'Close' }).first().click();

    await page.getByText('Upcoming', { exact: true }).click();
    const card = page.getByRole('article', { name: title });
    await expect(card).toContainText('Today');
    await expect(card.locator('img')).toBeVisible();

    await page.goto('/');
    const today = page.getByRole('region', { name: /LinkedIn posts/ });
    await expect(today).toContainText(title);
    await expect(today.getByRole('link', { name: 'Connect LinkedIn to post' }).first()).toBeVisible();
  });
});
