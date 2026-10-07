import { test as base, expect } from '@playwright/test';
import { API_URL } from './urls.js';

// An id far beyond anything the database will reach, for not-found checks.
export const MISSING_ID = 999_999_999_999;

export function uniqueName(label) {
  return `${label} E2E ${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

export function companyPayload(overrides = {}) {
  return {
    name: uniqueName('Acme AI'),
    website: 'https://acme.example',
    location: 'New York, NY',
    companySize: '51-200',
    status: 'Prospect',
    linkedinProfile: 'https://linkedin.com/company/acme-ai',
    notes: 'Created by Playwright',
    ...overrides,
  };
}

export function contactPayload(companyId, overrides = {}) {
  return {
    name: uniqueName('Jane Doe'),
    title: 'VP Engineering',
    email: 'jane.doe@acme.example',
    linkedinUrl: 'https://linkedin.com/in/jane-doe',
    relationshipStatus: 'Warm',
    lastContactedDate: '2026-10-01',
    nextFollowUpDate: '2026-10-15',
    notes: 'Created by Playwright',
    companyId,
    ...overrides,
  };
}

/**
 * `crm` fixture: creates test data through the API and deletes everything
 * the test created when it finishes, so runs never leave records behind.
 */
export const test = base.extend({
  crm: async ({ request }, use) => {
    const companyIds = new Set();
    const contactIds = new Set();
    const companyNames = new Set();
    const contactNames = new Set();

    const crm = {
      async createCompany(overrides = {}) {
        const response = await request.post(`${API_URL}/api/companies`, {
          data: companyPayload(overrides),
        });
        expect(response.ok(), await response.text()).toBeTruthy();
        const company = await response.json();
        companyIds.add(company.id);
        return company;
      },

      async createContact(companyId, overrides = {}) {
        const response = await request.post(`${API_URL}/api/contacts`, {
          data: contactPayload(companyId, overrides),
        });
        expect(response.ok(), await response.text()).toBeTruthy();
        const contact = await response.json();
        contactIds.add(contact.id);
        return contact;
      },

      async findCompanyByName(name) {
        const response = await request.get(`${API_URL}/api/companies`);
        const companies = await response.json();
        return companies.find((company) => company.name === name);
      },

      async findContactByName(name) {
        const response = await request.get(`${API_URL}/api/contacts`);
        const contacts = await response.json();
        return contacts.find((contact) => contact.name === name);
      },

      // Register records created some other way (the UI, or a raw request).
      trackCompany: (id) => companyIds.add(id),
      trackContact: (id) => contactIds.add(id),
      trackCompanyName: (name) => companyNames.add(name),
      trackContactName: (name) => contactNames.add(name),
    };

    await use(crm);

    for (const name of contactNames) {
      const contact = await crm.findContactByName(name);
      if (contact) contactIds.add(contact.id);
    }
    for (const name of companyNames) {
      const company = await crm.findCompanyByName(name);
      if (company) companyIds.add(company.id);
    }

    // Contacts reference companies, so they go first.
    for (const id of contactIds) {
      await request.delete(`${API_URL}/api/contacts/${id}`);
    }
    for (const id of companyIds) {
      await request.delete(`${API_URL}/api/companies/${id}`);
    }
  },
});

export { expect };
