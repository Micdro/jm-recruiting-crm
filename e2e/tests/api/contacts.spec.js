import { test, expect, contactPayload, MISSING_ID } from '../support/fixtures.js';

test.describe('Contacts API', () => {
  test('creates, reads, updates, and deletes a contact linked to a company', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const payload = contactPayload(company.id);
    let contact;

    await test.step('create', async () => {
      const response = await request.post('/api/contacts', { data: payload });
      expect(response.status()).toBe(200);

      contact = await response.json();
      crm.trackContact(contact.id);
      expect(contact.id).toEqual(expect.any(Number));
      expect(contact).toMatchObject({ ...payload, companyName: company.name });
    });

    await test.step('read by id', async () => {
      const response = await request.get(`/api/contacts/${contact.id}`);
      expect(response.status()).toBe(200);
      expect(await response.json()).toEqual(contact);
    });

    await test.step('appears in the list', async () => {
      const response = await request.get('/api/contacts');
      expect(response.status()).toBe(200);
      expect(await response.json()).toContainEqual(contact);
    });

    await test.step('update and move to another company', async () => {
      const newCompany = await crm.createCompany();
      const response = await request.put(`/api/contacts/${contact.id}`, {
        data: { ...payload, title: 'CTO', companyId: newCompany.id },
      });
      expect(response.status()).toBe(200);
      expect(await response.json()).toMatchObject({
        id: contact.id,
        title: 'CTO',
        companyId: newCompany.id,
        companyName: newCompany.name,
      });
    });

    await test.step('delete', async () => {
      const response = await request.delete(`/api/contacts/${contact.id}`);
      expect(response.status()).toBe(204);

      const afterDelete = await request.get(`/api/contacts/${contact.id}`);
      expect(afterDelete.status()).toBe(404);
    });
  });

  test('requires a name and a company', async ({ request }) => {
    const response = await request.post('/api/contacts', {
      data: contactPayload(null, { name: '' }),
    });

    expect(response.status()).toBe(400);
    const body = await response.json();
    expect(body.error).toBe('Validation failed');
    expect(body.messages).toEqual(
      expect.arrayContaining(['Contact name is required', 'Company ID is required']),
    );
  });

  test('rejects an invalid email', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const response = await request.post('/api/contacts', {
      data: contactPayload(company.id, { email: 'not-an-email' }),
    });

    expect(response.status()).toBe(400);
    expect((await response.json()).messages).toContain('Email must be valid');
  });

  test('returns 404 when the company does not exist', async ({ request }) => {
    const response = await request.post('/api/contacts', {
      data: contactPayload(MISSING_ID),
    });

    expect(response.status()).toBe(404);
  });

  test('returns 404 for a contact that does not exist', async ({ request, crm }) => {
    const company = await crm.createCompany();

    const get = await request.get(`/api/contacts/${MISSING_ID}`);
    expect(get.status()).toBe(404);

    const put = await request.put(`/api/contacts/${MISSING_ID}`, {
      data: contactPayload(company.id),
    });
    expect(put.status()).toBe(404);

    const del = await request.delete(`/api/contacts/${MISSING_ID}`);
    expect(del.status()).toBe(404);
  });
});
