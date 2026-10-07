import { test, expect, companyPayload, MISSING_ID } from '../support/fixtures.js';

test.describe('Companies API', () => {
  test('creates, reads, updates, and deletes a company', async ({ request, crm }) => {
    const payload = companyPayload();
    let company;

    await test.step('create', async () => {
      const response = await request.post('/api/companies', { data: payload });
      expect(response.status()).toBe(200);

      company = await response.json();
      crm.trackCompany(company.id);
      expect(company.id).toEqual(expect.any(Number));
      expect(company).toMatchObject(payload);
    });

    await test.step('read by id', async () => {
      const response = await request.get(`/api/companies/${company.id}`);
      expect(response.status()).toBe(200);
      expect(await response.json()).toEqual(company);
    });

    await test.step('appears in the list', async () => {
      const response = await request.get('/api/companies');
      expect(response.status()).toBe(200);
      expect(await response.json()).toContainEqual(company);
    });

    await test.step('update', async () => {
      const response = await request.put(`/api/companies/${company.id}`, {
        data: { ...payload, status: 'Client', notes: 'Signed retainer' },
      });
      expect(response.status()).toBe(200);
      expect(await response.json()).toMatchObject({
        id: company.id,
        status: 'Client',
        notes: 'Signed retainer',
      });
    });

    await test.step('delete', async () => {
      const response = await request.delete(`/api/companies/${company.id}`);
      expect(response.status()).toBe(204);

      const afterDelete = await request.get(`/api/companies/${company.id}`);
      expect(afterDelete.status()).toBe(404);
    });
  });

  test('rejects a company without a name', async ({ request }) => {
    const response = await request.post('/api/companies', {
      data: companyPayload({ name: '' }),
    });

    expect(response.status()).toBe(400);
    expect(await response.json()).toEqual({
      status: 400,
      error: 'Validation failed',
      messages: ['Company name is required'],
    });
  });

  test('rejects a company name over 255 characters', async ({ request }) => {
    const response = await request.post('/api/companies', {
      data: companyPayload({ name: 'A'.repeat(256) }),
    });

    expect(response.status()).toBe(400);
    const body = await response.json();
    expect(body.messages).toContain('Company name must be 255 characters or fewer');
  });

  test('returns 404 for a company that does not exist', async ({ request }) => {
    const get = await request.get(`/api/companies/${MISSING_ID}`);
    expect(get.status()).toBe(404);

    const put = await request.put(`/api/companies/${MISSING_ID}`, { data: companyPayload() });
    expect(put.status()).toBe(404);

    const del = await request.delete(`/api/companies/${MISSING_ID}`);
    expect(del.status()).toBe(404);
  });
});
