import { test, expect, daysFrom } from '../support/fixtures.js';

test.describe('Interviews API', () => {
  test('schedules an interview, shows it on Today until both debriefs are done, and cancels', async ({ request, crm }) => {
    const { today } = await crm.getJson('/api/dashboard');
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const entry = await crm.addToSearch(search.id, candidate.id, { stage: 'SUBMITTED' });

    const scheduled = await request.put(`/api/pipeline/${entry.id}/interview`, {
      data: { date: daysFrom(today, -1), time: '14:00', label: 'Round 1 with the CTO' },
    });
    expect(scheduled.status()).toBe(200);
    expect(await scheduled.json()).toMatchObject({
      stage: 'INTERVIEWING',
      interviewDate: daysFrom(today, -1),
      interviewLabel: 'Round 1 with the CTO',
      debriefDue: true,
    });
    const onToday = (await crm.getJson('/api/dashboard')).interviews.find((row) => row.id === entry.id);
    expect(onToday).toMatchObject({ debriefDue: true, candidateName: candidate.name });

    await request.post(`/api/pipeline/${entry.id}/debrief`, { data: { with: 'CANDIDATE', notes: 'Keen on the team' } });
    const both = await request.post(`/api/pipeline/${entry.id}/debrief`, { data: { with: 'CLIENT' } });
    expect(await both.json()).toMatchObject({ candidateDebriefed: true, clientDebriefed: true, debriefDue: false });
    expect((await crm.getJson('/api/dashboard')).interviews.some((row) => row.id === entry.id)).toBe(false);

    const cancelled = await request.delete(`/api/pipeline/${entry.id}/interview`);
    expect((await cancelled.json()).interviewDate).toBeNull();
    expect((await request.delete(`/api/pipeline/${entry.id}/interview`)).status()).toBe(409);
  });

  test('needs a date', async ({ request, crm }) => {
    const company = await crm.createCompany();
    const search = await crm.createSearch(company.id);
    const candidate = await crm.createCandidate();
    const entry = await crm.addToSearch(search.id, candidate.id, { stage: 'SUBMITTED' });

    expect((await request.put(`/api/pipeline/${entry.id}/interview`, { data: { label: 'Soon' } })).status()).toBe(400);
  });
});
