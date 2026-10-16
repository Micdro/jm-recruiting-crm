import { test, expect, uniqueName } from '../support/fixtures.js';

const ME = 'E2E Recruiter';

/** One group of messages from one sender, as LinkedIn's messaging page lays them out. */
const group = ({ name, slug, time, text, other }) => `<li class="msg-s-message-list__event">
  <div class="msg-s-message-group">
    <a class="msg-s-message-group__profile-link" href="https://www.linkedin.com/in/${slug}/"><img alt=""></a>
    <span class="msg-s-message-group__name">${name}</span>
    <time class="msg-s-message-group__timestamp">${time}</time>
    <div class="msg-s-event-listitem${other ? ' msg-s-event-listitem--other' : ''}">
      <p class="msg-s-event-listitem__body">${text}</p>
    </div>
  </div>
</li>`;

const nav = `<header class="global-nav"><div class="global-nav__me"><img class="global-nav__me-photo" alt="${ME}"></div></header>`;

/** A stand-in LinkedIn conversation with {@code them}, ending with {@code lastFrom}'s message. */
function conversation({ name, slug }, lastFrom, { chatWindow = false } = {}) {
  const mine = group({ name: ME, slug: 'recruiter-e2e', time: '10:02 AM', text: 'Saw the FDE opening. Open to a quick call?' });
  const theirs = group({ name, slug, time: '11:15 AM', text: 'Thanks, happy to talk next week.', other: true });
  const messages = lastFrom === 'them' ? mine + theirs : theirs + mine;
  const thread = chatWindow
    ? `<div class="msg-overlay-conversation-bubble">
         <header class="msg-overlay-bubble-header"><h2 class="msg-overlay-bubble-header__title">
           <a href="https://www.linkedin.com/in/${slug}/">${name}</a></h2></header>
         <ul class="msg-s-message-list">${messages}</ul>
       </div>`
    : `<main><div class="msg-convo-wrapper"><div class="msg-thread">
         <div class="msg-entity-lockup"><h2 class="msg-entity-lockup__entity-title">${name}</h2>
           <a class="msg-thread__link-to-profile" href="https://www.linkedin.com/in/${slug}/">View profile</a></div>
         <ul class="msg-s-message-list">${messages}</ul>
       </div></div></main>`;
  return `<!doctype html><title>Messaging | LinkedIn</title><body>${nav}${thread}</body>`;
}

/** Opens a stand-in LinkedIn page, clicks Log to JM CRM on it, and returns the CRM tab it opens. */
async function logFrom(page, context, path, html) {
  await page.goto('/linkedin');
  const href = await page.getByRole('link', { name: 'Log to JM CRM' }).getAttribute('href');
  expect(href.startsWith('javascript:')).toBeTruthy();
  await context.route(`https://www.linkedin.com${path}`, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const linkedIn = await context.newPage();
  await linkedIn.goto(`https://www.linkedin.com${path}`);
  const [crm] = await Promise.all([
    context.waitForEvent('page'),
    linkedIn.evaluate(decodeURIComponent(href.slice('javascript:'.length))),
  ]);
  await expect(crm.getByRole('heading', { name: 'Log from LinkedIn', level: 1 })).toBeVisible();
  return crm;
}

test.describe('Log from LinkedIn', () => {
  test('logs a reply from a conversation, finding the contact by their profile', async ({ page, context, crm }) => {
    const slug = `dana-reply-${Date.now()}`;
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      name: uniqueName('Dana Reyes'),
      linkedinUrl: `https://www.linkedin.com/in/${slug}/`,
    });

    const tab = await logFrom(page, context, `/messaging/thread/${slug}/`, conversation({ name: contact.name, slug }, 'them'));
    await expect(tab.getByText('Their message was the last one, 11:15 AM.')).toBeVisible();
    await expect(tab.getByText('Thanks, happy to talk next week.').first()).toBeVisible();
    const buttons = tab.locator('.form-actions .btn');
    await expect(buttons.first()).toHaveText('Log their reply');
    await expect(buttons.first()).toHaveClass(/btn-primary/);

    await tab.getByRole('button', { name: 'Log their reply' }).click();
    await expect(tab.locator('.linkedin-done')).toContainText(`Logged ${contact.name}’s reply. Now Replied.`);

    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved.status).toBe('REPLIED');
    const history = await crm.getJson(`/api/activities?contactId=${contact.id}`);
    expect(history.find((activity) => activity.type === 'REPLY')?.summary).toContain('Thanks, happy to talk next week.');

    // Clicking the button on the same conversation again warns before logging twice.
    const again = await logFrom(page, context, `/messaging/thread/${slug}/`, conversation({ name: contact.name, slug }, 'them'));
    await expect(again.getByText(`You already logged a reply from ${contact.name} today.`)).toBeVisible();
  });

  test('logs your own message from a chat window as a sequence touch', async ({ page, context, crm }) => {
    const slug = `sam-touch-${Date.now()}`;
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, {
      name: uniqueName('Sam Ortiz'),
      linkedinUrl: `https://www.linkedin.com/in/${slug}`,
    });

    const tab = await logFrom(
      page,
      context,
      `/in/${slug}/`,
      conversation({ name: contact.name, slug }, 'me', { chatWindow: true }),
    );
    await expect(tab.getByText('Your message was the last one, 10:02 AM.')).toBeVisible();
    await expect(tab.locator('.form-actions .btn').first()).toHaveText('Log my message');

    await tab.getByRole('button', { name: 'Log my message' }).click();
    await expect(tab.locator('.linkedin-done')).toContainText(`Logged your LinkedIn message to ${contact.name}. Now In sequence.`);
    const saved = await crm.getJson(`/api/contacts/${contact.id}`);
    expect(saved).toMatchObject({ status: 'IN_SEQUENCE', touchCount: 1 });
  });

  test('offers to add someone who is not in the CRM yet', async ({ page, context }) => {
    const slug = `new-person-${Date.now()}`;
    const name = uniqueName('Priya Raman');
    const tab = await logFrom(page, context, `/messaging/thread/${slug}/`, conversation({ name, slug }, 'them'));
    await expect(tab.getByText('Not in the CRM yet')).toBeVisible();
    await tab.getByRole('link', { name: `Add ${name}` }).click();
    await expect(tab.getByRole('heading', { name: 'Add as' })).toBeVisible();
    await expect(tab.getByLabel('LinkedIn profile')).toHaveValue(`https://www.linkedin.com/in/${slug}/`);
  });
});
