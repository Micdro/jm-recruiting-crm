import { test, expect, isoDate } from '../support/fixtures.js';

test.describe('Bulk actions API', () => {
  test('adds contacts to today, skips the ones it should, and undoes in one step', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const ready = await crm.createContact(company.id);
    const closed = await crm.createContact(company.id, { status: 'DO_NOT_CONTACT' });

    const added = await request.post('/api/contacts/bulk', { data: { ids: [ready.id, closed.id], action: 'TODAY' } });
    expect(added.status()).toBe(200);
    const result = await added.json();
    expect(result).toMatchObject({ changed: 1, skipped: [{ id: closed.id, name: closed.name, reason: 'Marked Do not contact' }] });
    expect(await crm.getJson(`/api/contacts/${ready.id}`)).toMatchObject({ nextActionDate: isoDate(0), nextActionType: 'LINKEDIN' });

    const undone = await request.post(`/api/contacts/bulk/${result.undoId}/undo`);
    expect(await undone.json()).toMatchObject({ changed: 1, skipped: [] });
    expect((await crm.getJson(`/api/contacts/${ready.id}`)).nextActionDate).toBeNull();

    expect((await request.post(`/api/contacts/bulk/${result.undoId}/undo`)).status()).toBe(409);
  });

  test('deletes only the companies with nothing in them', async ({ request, crm }) => {
    const busy = await crm.createCompany();
    await crm.createContact(busy.id);
    const empty = await crm.createCompany();

    const deleted = await request.post('/api/companies/bulk', { data: { ids: [busy.id, empty.id], action: 'DELETE' } });
    const result = await deleted.json();
    expect(result.changed).toBe(1);
    expect(result.skipped).toEqual([{ id: busy.id, name: busy.name, reason: 'Has contacts; delete or move them first' }]);
    expect((await request.get(`/api/companies/${empty.id}`)).status()).toBe(404);

    await request.post(`/api/companies/bulk/${result.undoId}/undo`);
    expect((await request.get(`/api/companies/${empty.id}`)).status()).toBe(200);
  });

  test('needs records and a choice', async ({ request, crm }) => {
    const company = await crm.createCompany();

    expect((await request.post('/api/contacts/bulk', { data: { ids: [], action: 'TODAY' } })).status()).toBe(400);
    expect((await request.post('/api/contacts/bulk', { data: { ids: [1] } })).status()).toBe(400);
    expect((await request.post('/api/companies/bulk', { data: { ids: [company.id], action: 'PRIORITY' } })).status()).toBe(400);
    expect((await request.post('/api/companies/bulk', { data: { ids: [company.id], action: 'SOMETHING' } })).status()).toBe(400);
  });
});
