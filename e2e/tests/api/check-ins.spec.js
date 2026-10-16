import { test, expect, daysFrom } from '../support/fixtures.js';

test.describe('Guarantee check-ins API', () => {
  test('a check-in is due 30 days after the start, and recording it moves on to the 60-day one', async ({ request, crm }) => {
    const { today } = await crm.getJson('/api/dashboard');
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const placement = await crm.createPlacement({
      searchId: search.id,
      candidateId: candidate.id,
      startDate: daysFrom(today, -35),
      baseSalary: 200000,
    });
    expect(placement).toMatchObject({ checkInDays: 30, checkInDue: true, checkInDate: daysFrom(today, -5) });
    expect((await crm.getJson('/api/dashboard')).checkIns.some((row) => row.id === placement.id)).toBe(true);

    const checkedIn = await request.post(`/api/placements/${placement.id}/check-in`, { data: { notes: 'All good' } });
    expect(checkedIn.status()).toBe(200);
    expect(await checkedIn.json()).toMatchObject({
      checkedInOn: today,
      checkInDays: 60,
      checkInDate: daysFrom(today, 25),
      checkInDue: false,
    });
    expect((await crm.getJson('/api/dashboard')).checkIns.some((row) => row.id === placement.id)).toBe(false);
    expect((await request.post(`/api/placements/${placement.id}/check-in`, { data: {} })).status()).toBe(409);
  });
});
