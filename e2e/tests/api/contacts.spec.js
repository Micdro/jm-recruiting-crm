import { test, expect, contactPayload, isoDate, MISSING_ID } from '../support/fixtures.js';

test.describe('Contacts API', () => {
  test('creates, updates, and deletes a contact', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const payload = contactPayload(company.id);

    const created = await request.post('/api/contacts', { data: payload });
    expect(created.status()).toBe(201);
    const contact = await created.json();
    crm.track('contacts', contact.id);
    expect(contact).toMatchObject({
      name: payload.name,
      email: payload.email,
      status: 'NEW',
      touchCount: 0,
      maxTouches: 4,
      companyId: company.id,
      companyName: company.name,
    });

    const updated = await request.put(`/api/contacts/${contact.id}`, {
      data: { ...payload, title: 'CTO', nextActionDate: isoDate(2), nextActionType: 'CALL', ownerId: contact.ownerId },
    });
    expect(updated.status()).toBe(200);
    expect(await updated.json()).toMatchObject({ title: 'CTO', nextActionDate: isoDate(2), nextActionType: 'CALL' });

    expect((await request.delete(`/api/contacts/${contact.id}`)).status()).toBe(204);
    expect((await request.get(`/api/contacts/${contact.id}`)).status()).toBe(404);
  });

  test('requires a company that exists', async ({ request }) => {
    const missingCompany = await request.post('/api/contacts', { data: contactPayload(MISSING_ID) });
    expect(missingCompany.status()).toBe(404);

    const noCompany = await request.post('/api/contacts', { data: contactPayload(null) });
    expect(noCompany.status()).toBe(400);
    expect((await noCompany.json()).messages).toContain('Company ID is required');
  });

  test('rejects an invalid email', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const response = await request.post('/api/contacts', { data: contactPayload(company.id, { email: 'not-an-email' }) });

    expect(response.status()).toBe(400);
    expect((await response.json()).messages).toContain('Email must be valid');
  });

  test('runs the four-touch sequence', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);
    const log = (data) => request.post(`/api/contacts/${contact.id}/activities`, { data });

    await test.step('touches 1 to 3 schedule the next touch', async () => {
      for (const [touch, type] of [
        [1, 'EMAIL'],
        [2, 'LINKEDIN'],
        [3, 'EMAIL'],
      ]) {
        const response = await log({ type });
        expect(response.status()).toBe(200);
        const body = await response.json();
        expect(body).toMatchObject({ touchCount: touch, status: 'IN_SEQUENCE', lastContactedDate: isoDate(0) });
        expect(body.nextActionDate > isoDate(0)).toBeTruthy();
      }
    });

    await test.step('touch 4 ends the sequence', async () => {
      const response = await log({ type: 'CALL', notes: 'Left a voicemail' });
      expect(await response.json()).toMatchObject({ touchCount: 4, status: 'NO_RESPONSE', nextActionDate: null });
    });

    await test.step('a fifth touch is refused', async () => {
      const response = await log({ type: 'EMAIL' });
      expect(response.status()).toBe(409);
    });

    await test.step('a reply moves them to Replied with a response due today', async () => {
      const response = await log({ type: 'REPLY', notes: 'Open to talking about an FDE search' });
      expect(await response.json()).toMatchObject({ status: 'REPLIED', nextActionDate: isoDate(0), nextActionType: 'EMAIL' });
    });

    await test.step('the history shows every step, newest first', async () => {
      const response = await request.get('/api/activities', { params: { contactId: contact.id } });
      const activities = await response.json();
      expect(activities.map((activity) => activity.type)).toEqual(['REPLY', 'CALL', 'EMAIL', 'LINKEDIN', 'EMAIL']);
      expect(activities[1].summary).toBe('Touch 4 of 4: Left a voicemail');
      expect(activities.filter((activity) => activity.touch)).toHaveLength(4);
    });
  });

  test('restarts a finished sequence', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);
    for (let i = 0; i < 4; i += 1) {
      await request.post(`/api/contacts/${contact.id}/activities`, { data: { type: 'EMAIL' } });
    }

    const response = await request.post(`/api/contacts/${contact.id}/restart-sequence`);
    expect(await response.json()).toMatchObject({ status: 'NEW', touchCount: 0, nextActionDate: isoDate(0) });
  });

  test('lists only contacts that are due', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const due = await crm.createContact(company.id, { nextActionDate: isoDate(-1), nextActionType: 'EMAIL' });
    await crm.createContact(company.id, { nextActionDate: isoDate(5), nextActionType: 'EMAIL' });

    const response = await request.get('/api/contacts', { params: { companyId: company.id, due: true } });
    const contacts = await response.json();
    expect(contacts.map((contact) => contact.id)).toEqual([due.id]);
  });
});
