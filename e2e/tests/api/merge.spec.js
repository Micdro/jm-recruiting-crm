import { test, expect, uniqueEmail } from '../support/fixtures.js';

test.describe('Merge API', () => {
  test('merges a duplicate contact into the one kept', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const keep = await crm.createContact(company.id, { email: null, notes: 'Met at a conference' });
    const email = uniqueEmail('duplicate');
    const duplicate = await crm.createContact(company.id, { email, notes: 'Prefers mornings' });
    await request.post(`/api/contacts/${duplicate.id}/activities`, { data: { type: 'LINKEDIN' } });

    const merged = await request.post('/api/merge', { data: { kind: 'CONTACT', keepId: keep.id, mergeId: duplicate.id } });
    expect(merged.status()).toBe(200);
    expect(await merged.json()).toMatchObject({ keptName: keep.name, mergedName: duplicate.name });

    expect((await request.get(`/api/contacts/${duplicate.id}`)).status()).toBe(404);
    expect(await crm.getJson(`/api/contacts/${keep.id}`)).toMatchObject({
      email,
      notes: 'Met at a conference\n\nPrefers mornings',
    });
    const history = await crm.getJson(`/api/activities?contactId=${keep.id}`);
    expect(history.map((activity) => activity.type)).toContain('LINKEDIN');
    expect(history.map((activity) => activity.summary)).toContainEqual(expect.stringContaining(`Merged in ${duplicate.name}`));
  });

  test('needs two different records that exist', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);

    expect((await request.post('/api/merge', { data: { kind: 'CONTACT', keepId: contact.id, mergeId: contact.id } })).status()).toBe(400);
    expect((await request.post('/api/merge', { data: { kind: 'CONTACT', keepId: contact.id } })).status()).toBe(400);
    expect((await request.post('/api/merge', { data: { kind: 'CONTACT', keepId: contact.id, mergeId: 999999999999 } })).status()).toBe(404);
  });
});
