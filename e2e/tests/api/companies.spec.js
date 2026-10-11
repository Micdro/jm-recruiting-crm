import { test, expect, companyPayload, MISSING_ID } from '../support/fixtures.js';
import { API_URL } from '../support/urls.js';

test.describe('Companies API', () => {
  test('creates, reads, updates, and deletes a company', async ({ request, crm }) => {
    const payload = companyPayload();
    let company;

    await test.step('create', async () => {
      const response = await request.post('/api/companies', { data: payload });
      expect(response.status()).toBe(201);

      company = await response.json();
      crm.track('companies', company.id);
      expect(company.id).toEqual(expect.any(Number));
      expect(company).toMatchObject(payload);
      expect(company.ownerName).toBeTruthy();
      expect(company.contactCount).toBe(0);
    });

    await test.step('read by id', async () => {
      const response = await request.get(`/api/companies/${company.id}`);
      expect(response.status()).toBe(200);
      expect(await response.json()).toMatchObject({ id: company.id, name: payload.name });
    });

    await test.step('appears in the list', async () => {
      const response = await request.get('/api/companies');
      const companies = await response.json();
      expect(companies.map((item) => item.id)).toContain(company.id);
    });

    await test.step('update', async () => {
      const response = await request.put(`/api/companies/${company.id}`, {
        data: { ...payload, status: 'CLIENT', notes: 'Signed exclusive', ownerId: company.ownerId },
      });
      expect(response.status()).toBe(200);
      expect(await response.json()).toMatchObject({ id: company.id, status: 'CLIENT', notes: 'Signed exclusive' });
    });

    await test.step('the status change is in the history', async () => {
      const response = await request.get('/api/activities', { params: { companyId: company.id } });
      const activities = await response.json();
      expect(activities[0]).toMatchObject({ type: 'STATUS_CHANGE', summary: 'Status changed from Prospecting to Client' });
    });

    await test.step('delete', async () => {
      const response = await request.delete(`/api/companies/${company.id}`);
      expect(response.status()).toBe(204);

      const afterDelete = await request.get(`/api/companies/${company.id}`);
      expect(afterDelete.status()).toBe(404);
    });
  });

  test('rejects a company without a name', async ({ request }) => {
    const response = await request.post('/api/companies', { data: companyPayload({ name: '' }) });

    expect(response.status()).toBe(400);
    expect(await response.json()).toEqual({
      status: 400,
      error: 'Validation failed',
      messages: ['Company name is required'],
    });
  });

  test('rejects a duplicate company name', async ({ request, crm }) => {
    const company = await crm.createCompany();

    const response = await request.post('/api/companies', { data: companyPayload({ name: company.name.toUpperCase() }) });
    expect(response.status()).toBe(409);
  });

  test('refuses to delete a company that still has contacts', async ({ request, crm }) => {
    const company = await crm.createCompany();
    await crm.createContact(company.id);

    const response = await request.delete(`/api/companies/${company.id}`);
    expect(response.status()).toBe(409);
    expect((await response.json()).messages[0]).toContain('Company has contacts');
  });

  test('returns 404 for a company that does not exist', async ({ request }) => {
    expect((await request.get(`/api/companies/${MISSING_ID}`)).status()).toBe(404);
    expect((await request.delete(`/api/companies/${MISSING_ID}`)).status()).toBe(404);
  });

  test('requires sign-in', async ({ playwright }) => {
    const anonymous = await playwright.request.newContext({ baseURL: API_URL, storageState: { cookies: [], origins: [] } });
    expect((await anonymous.get('/api/companies')).status()).toBe(401);
    await anonymous.dispose();
  });
});
