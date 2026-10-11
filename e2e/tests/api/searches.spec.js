import { test, expect, isoDate } from '../support/fixtures.js';

test.describe('Searches, pipeline and placements API', () => {
  test('takes a candidate from sourced to placed', async ({ request, crm }) => {
    const company = await crm.createCompany({ status: 'DISCOVERY_COMPLETED' });
    const manager = await crm.createContact(company.id, { title: 'VP Engineering' });
    const search = await crm.createSearch(company.id, { hiringManagerId: manager.id });
    const candidate = await crm.createCandidate();

    await test.step('opening a search makes the company a client', async () => {
      expect(search).toMatchObject({ status: 'INTAKE', hiringManagerName: manager.name, feePercent: 25 });
      const refreshed = await crm.getJson(`/api/companies/${company.id}`);
      expect(refreshed).toMatchObject({ status: 'PLACING', openSearchCount: 1 });
    });

    let entry;
    await test.step('add the candidate to the search', async () => {
      const response = await request.post('/api/pipeline', { data: { searchId: search.id, candidateId: candidate.id } });
      expect(response.status()).toBe(201);
      entry = await response.json();
      expect(entry).toMatchObject({ stage: 'SOURCED', candidateName: candidate.name, companyName: company.name });

      const duplicate = await request.post('/api/pipeline', { data: { searchId: search.id, candidateId: candidate.id } });
      expect(duplicate.status()).toBe(409);
    });

    await test.step('shortlist with a rationale', async () => {
      const response = await request.put(`/api/pipeline/${entry.id}`, {
        data: { stage: 'SHORTLISTED', rationale: 'Ran production ML for a regulated card issuer' },
      });
      expect(await response.json()).toMatchObject({ stage: 'SHORTLISTED' });

      const refreshed = await crm.getJson(`/api/searches/${search.id}`);
      expect(refreshed.stageCounts).toEqual({ SHORTLISTED: 1 });
    });

    await test.step('record the placement; the fee comes from the search terms', async () => {
      const response = await request.post('/api/placements', {
        data: { searchId: search.id, candidateId: candidate.id, startDate: isoDate(21), baseSalary: 200000 },
      });
      expect(response.status()).toBe(201);
      const placement = await response.json();
      crm.track('placements', placement.id);
      expect(placement).toMatchObject({ feePercent: 25, feeAmount: 50000, invoiceStatus: 'NOT_INVOICED', guaranteeEndDate: isoDate(111) });
    });

    await test.step('the candidate is placed and the pipeline shows it', async () => {
      const pipeline = await crm.getJson(`/api/searches/${search.id}/pipeline`);
      expect(pipeline[0]).toMatchObject({ stage: 'PLACED', placed: true });
      expect(await crm.getJson(`/api/candidates/${candidate.id}`)).toMatchObject({ status: 'PLACED' });
    });

    await test.step('a candidate with a placement cannot be deleted', async () => {
      expect((await request.delete(`/api/candidates/${candidate.id}`)).status()).toBe(409);
    });
  });

  test('the hiring manager must work at the search company', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const otherCompany = await crm.createCompany();
    const outsider = await crm.createContact(otherCompany.id);

    const response = await request.post('/api/searches', {
      data: { companyId: company.id, hiringManagerId: outsider.id, title: 'FDE', track: 'PRODUCTION_AI_ENGINEERING' },
    });
    expect(response.status()).toBe(400);
  });

  test('the dashboard counts today’s touches', async ({ request, crm }) => {
    const before = await crm.getJson('/api/dashboard');
    const company = await crm.createCompany();
    const contact = await crm.createContact(company.id);

    await request.post(`/api/contacts/${contact.id}/activities`, { data: { type: 'EMAIL' } });

    const after = await crm.getJson('/api/dashboard');
    expect(after.myTouchesToday).toBeGreaterThanOrEqual(before.myTouchesToday + 1);
    expect(after.dailyTouchTarget).toBe(30);
    expect(after.dailyTouchStretch).toBe(50);
  });
});
