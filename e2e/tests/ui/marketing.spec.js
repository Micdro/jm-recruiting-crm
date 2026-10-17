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
