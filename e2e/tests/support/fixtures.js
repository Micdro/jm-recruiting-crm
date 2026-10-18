import { test as base, expect } from '@playwright/test';
import { API_URL, AUTH_FILE } from './urls.js';
import { savedXsrfToken } from './session.js';

// An id far beyond anything the database will reach, for not-found checks.
export const MISSING_ID = 999_999_999_999;

export function uniqueName(label) {
  return `${label} E2E ${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

export function uniqueEmail(label = 'person') {
  return `${label}.${Date.now()}.${Math.random().toString(36).slice(2, 7)}@e2e.example`;
}

/** A date relative to today, as YYYY-MM-DD in local time. */
export function isoDate(offsetDays = 0) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * The weekday a number of days after a YYYY-MM-DD date, moved off a weekend: where Later puts a
 * follow-up (1 for Tomorrow, 7 for Next week), counted from the server's today.
 */
export function laterFrom(todayIso, days) {
  const [year, month, day] = todayIso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + days));
  while (date.getUTCDay() === 0 || date.getUTCDay() === 6) date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/** A YYYY-MM-DD date some days from another, by the calendar (weekends included). */
export function daysFrom(isoDay, days) {
  const [year, month, day] = isoDay.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

export function companyPayload(overrides = {}) {
  return {
    name: uniqueName('Acme AI'),
    website: 'https://acme.example',
    location: 'New York, NY',
    companySize: '51-200',
    status: 'INITIAL_OUTREACH',
    focus: 'AI fraud detection for regional banks',
    industry: 'BANKS',
    segments: ['FRAUD_IDENTITY_RISK', 'DECISIONING'],
    priority: 'HIGH',
    hiringNeed: 'FDE',
    hiringTimeline: 'ONE_TO_TWO_MONTHS',
    anticipatedHires: 2,
    leadSource: 'LINKEDIN',
    linkedinProfile: 'https://linkedin.com/company/acme-ai',
    notes: 'Created by Playwright',
    ...overrides,
  };
}

export function contactPayload(companyId, overrides = {}) {
  return {
    name: uniqueName('Jane Doe'),
    title: 'VP Engineering',
    email: uniqueEmail('jane'),
    linkedinUrl: 'https://linkedin.com/in/jane-doe',
    status: 'NEW',
    notes: 'Created by Playwright',
    companyId,
    ...overrides,
  };
}

export function candidatePayload(overrides = {}) {
  return {
    name: uniqueName('Avery Chen'),
    email: uniqueEmail('avery'),
    currentTitle: 'Staff ML Platform Engineer',
    currentCompany: 'Cardwise',
    location: 'New York, NY',
    track: 'PRODUCTION_AI_ENGINEERING',
    regulatedExperience: true,
    productionEvidence: 'Owned real-time model serving for a card issuer; led two Sev-1 recoveries.',
    ...overrides,
  };
}

export function searchPayload(companyId, overrides = {}) {
  return {
    companyId,
    title: uniqueName('Forward Deployed Engineer'),
    track: 'PRODUCTION_AI_ENGINEERING',
    engagementType: 'EXCLUSIVE',
    feePercent: 25,
    baseMin: 180000,
    baseMax: 230000,
    productionOutcome: 'Live fraud models running at two tier-1 banks',
    ...overrides,
  };
}

export const test = base.extend({
  // API tests send the CSRF header on every request, as the React app does.
  extraHTTPHeaders: async ({ extraHTTPHeaders }, use, testInfo) => {
    if (testInfo.project.name === 'api') {
      await use({ ...extraHTTPHeaders, 'X-XSRF-TOKEN': savedXsrfToken() });
    } else {
      await use(extraHTTPHeaders);
    }
  },

  /**
   * `crm` creates test data through the API and deletes everything the test created
   * when it finishes, so runs never leave records behind.
   */
  crm: async ({ playwright }, use) => {
    const api = await playwright.request.newContext({
      baseURL: API_URL,
      storageState: AUTH_FILE,
      extraHTTPHeaders: { 'X-XSRF-TOKEN': savedXsrfToken() },
    });

    const created = {
      posts: new Set(),
      postTemplates: new Set(),
      templates: new Set(),
      placements: new Set(),
      searches: new Set(),
      candidates: new Set(),
      contacts: new Set(),
      companies: new Set(),
    };
    const named = { companies: new Set(), contacts: new Set(), candidates: new Set() };

    async function createVia(path, data, bucket) {
      const response = await api.post(path, { data });
      expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
      const record = await response.json();
      if (bucket) created[bucket].add(record.id);
      return record;
    }

    async function findByName(path, name) {
      const response = await api.get(path);
      const records = await response.json();
      return records.find((record) => record.name === name);
    }

    const crm = {
      api,
      createCompany: (overrides) => createVia('/api/companies', companyPayload(overrides), 'companies'),
      createContact: (companyId, overrides) => createVia('/api/contacts', contactPayload(companyId, overrides), 'contacts'),
      createCandidate: (overrides) => createVia('/api/candidates', candidatePayload(overrides), 'candidates'),
      createSearch: (companyId, overrides) => createVia('/api/searches', searchPayload(companyId, overrides), 'searches'),
      addToSearch: (searchId, candidateId, overrides = {}) =>
        createVia('/api/pipeline', { searchId, candidateId, ...overrides }),
      createPlacement: (data) => createVia('/api/placements', data, 'placements'),
      createTemplate: (data) => createVia('/api/templates', data, 'templates'),
      getJson: async (path) => {
        const response = await api.get(path);
        expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
        return response.json();
      },
      findCompanyByName: (name) => findByName('/api/companies', name),
      findContactByName: (name) => findByName('/api/contacts', name),
      findCandidateByName: (name) => findByName('/api/candidates', name),

      // Register records created some other way (the UI, an import, or a raw request).
      track: (bucket, id) => created[bucket].add(id),
      trackName: (bucket, name) => named[bucket].add(name),
    };

    await use(crm);

    for (const name of named.contacts) {
      const contact = await crm.findContactByName(name);
      if (contact) created.contacts.add(contact.id);
    }
    for (const name of named.candidates) {
      const candidate = await crm.findCandidateByName(name);
      if (candidate) created.candidates.add(candidate.id);
    }
    for (const name of named.companies) {
      const company = await crm.findCompanyByName(name);
      if (company) created.companies.add(company.id);
    }

    // Delete in dependency order: posts, templates and placements, then searches (their pipeline goes with them),
    // then candidates and contacts, then companies.
    for (const id of created.posts) await api.delete(`/api/marketing/posts/${id}`);
    for (const id of created.postTemplates) await api.delete(`/api/marketing/templates/${id}`);
    for (const id of created.templates) await api.delete(`/api/templates/${id}`);
    for (const id of created.placements) await api.delete(`/api/placements/${id}`);
    for (const id of created.searches) await api.delete(`/api/searches/${id}`);
    for (const id of created.candidates) await api.delete(`/api/candidates/${id}`);
    for (const id of created.contacts) await api.delete(`/api/contacts/${id}`);
    for (const id of created.companies) await api.delete(`/api/companies/${id}`);

    await api.dispose();
  },
});

export { expect };
