import { test, expect, uniqueEmail, uniqueName } from '../support/fixtures.js';

test.describe('Import API', () => {
  test('imports contacts, creates their companies, and skips duplicates', async ({ request, crm }) => {
    const companyName = uniqueName('Import Bank AI');
    const email = uniqueEmail('lee');

    const response = await request.post('/api/import/contacts', {
      data: {
        duplicateMode: 'SKIP',
        rows: [
          { companyName, firstName: 'Lee', lastName: 'Park', email, touchCount: 2, status: 'IN_SEQUENCE' },
          { companyName: companyName.toLowerCase(), name: 'Lee Park', email: email.toUpperCase() },
          { companyName, email: uniqueEmail('nobody') },
        ],
      },
    });
    expect(response.status()).toBe(200);

    const company = await crm.findCompanyByName(companyName);
    crm.track('companies', company.id);
    const contacts = await crm.getJson(`/api/contacts?companyId=${company.id}`);
    contacts.forEach((contact) => crm.track('contacts', contact.id));

    expect(await response.json()).toEqual({
      created: 1,
      updated: 0,
      skipped: 1,
      companiesCreated: 1,
      errors: [{ row: 4, message: 'Name is missing' }],
    });
    expect(contacts).toHaveLength(1);
    expect(contacts[0]).toMatchObject({ name: 'Lee Park', touchCount: 2, status: 'IN_SEQUENCE' });
  });

  test('imports candidates', async ({ request, crm }) => {
    const name = uniqueName('Morgan Lee');
    const response = await request.post('/api/import/candidates', {
      data: {
        rows: [{ name, currentTitle: 'Forward Deployed Engineer', track: 'PRODUCTION_AI_ENGINEERING', source: 'REFERRAL' }],
      },
    });
    expect(await response.json()).toMatchObject({ created: 1, errors: [] });

    const candidate = await crm.findCandidateByName(name);
    crm.track('candidates', candidate.id);
    expect(candidate).toMatchObject({ status: 'ACTIVE', track: 'PRODUCTION_AI_ENGINEERING', source: 'REFERRAL' });
  });
});
