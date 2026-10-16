import { test, expect, isoDate, laterFrom } from '../support/fixtures.js';

test.describe('Undo and Later API', () => {
  test('undoes a touch once, and the history entry goes with it', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id, { nextActionDate: isoDate(0), nextActionType: 'LINKEDIN' });

    const logged = await request.post(`/api/contacts/${contact.id}/activities`, { data: { type: 'LINKEDIN' } });
    expect(logged.status()).toBe(200);
    expect(await logged.json()).toMatchObject({ touchCount: 1, status: 'IN_SEQUENCE' });

    const undone = await request.post(`/api/contacts/${contact.id}/undo`);
    expect(undone.status()).toBe(200);
    expect(await undone.json()).toMatchObject({ touchCount: 0, status: 'NEW', nextActionDate: isoDate(0) });
    expect(await crm.getJson(`/api/activities?contactId=${contact.id}`)).toHaveLength(0);

    const again = await request.post(`/api/contacts/${contact.id}/undo`);
    expect(again.status()).toBe(409);
    expect((await again.json()).messages[0]).toContain('nothing to undo');
  });

  test('moves a contact, a company and a candidate to later, and undoes each', async ({ request, crm }) => {
    const { today } = await crm.getJson('/api/dashboard');

    const company = await crm.createCompany({ followUpDate: isoDate(0) });
    const contact = await crm.createContact(company.id, { nextActionDate: isoDate(0), nextActionType: 'LINKEDIN' });
    const candidate = await crm.createCandidate({ nextActionDate: isoDate(0), nextActionType: 'CALL' });

    const movedContact = await request.post(`/api/contacts/${contact.id}/snooze`, { data: { until: 'TOMORROW' } });
    expect(await movedContact.json()).toMatchObject({ nextActionDate: laterFrom(today, 1), nextActionType: 'LINKEDIN' });
    const history = await crm.getJson(`/api/activities?contactId=${contact.id}`);
    expect(history).toHaveLength(1);
    expect(history[0].type).toBe('STATUS_CHANGE');
    expect(history[0].summary).toContain('Next action moved to');

    const movedCompany = await request.post(`/api/companies/${company.id}/snooze`, { data: { until: 'NEXT_WEEK' } });
    expect(await movedCompany.json()).toMatchObject({ followUpDate: laterFrom(today, 7) });

    const movedCandidate = await request.post(`/api/candidates/${candidate.id}/snooze`, { data: { until: 'TOMORROW' } });
    expect(await movedCandidate.json()).toMatchObject({ nextActionDate: laterFrom(today, 1), nextActionType: 'CALL' });

    expect(await (await request.post(`/api/contacts/${contact.id}/undo`)).json())
      .toMatchObject({ nextActionDate: isoDate(0) });
    expect(await (await request.post(`/api/companies/${company.id}/undo`)).json())
      .toMatchObject({ followUpDate: isoDate(0) });
    expect(await (await request.post(`/api/candidates/${candidate.id}/undo`)).json())
      .toMatchObject({ nextActionDate: isoDate(0) });
    expect(await crm.getJson(`/api/activities?contactId=${contact.id}`)).toHaveLength(0);
  });

  test('needs a choice, and something scheduled to move', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const unscheduled = await crm.createContact(company.id);

    const noChoice = await request.post(`/api/contacts/${unscheduled.id}/snooze`, { data: {} });
    expect(noChoice.status()).toBe(400);
    const badChoice = await request.post(`/api/contacts/${unscheduled.id}/snooze`, { data: { until: 'SOMEDAY' } });
    expect(badChoice.status()).toBe(400);

    const nothingScheduled = await request.post(`/api/contacts/${unscheduled.id}/snooze`, { data: { until: 'TOMORROW' } });
    expect(nothingScheduled.status()).toBe(409);
    expect((await nothingScheduled.json()).messages[0]).toContain('no next action to move');
  });
});
