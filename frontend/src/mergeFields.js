// What the merge dialog shows for each kind of record, and what merging does with each detail.
import { formatDate } from './format.js';
import { CANDIDATE_STATUS, COMPANY_STATUS, INDUSTRY, OUTREACH_STATUS, PRIORITY } from './labels.js';
import { profileKey } from './linkedin.js';

const linkedin = (url) => (url ? (profileKey(url) ?? url) : null);
const date = (value) => (value ? formatDate(value) : null);

export const CONTACT_MERGE = {
  moves: 'history, and searches where they’re the hiring manager',
  fields: [
    { label: 'Name', value: (record) => record.name },
    { label: 'Company', value: (record) => record.companyName },
    { label: 'Title', value: (record) => record.title, fill: true },
    { label: 'Email', value: (record) => record.email, fill: true },
    { label: 'Phone', value: (record) => record.phone, fill: true },
    { label: 'LinkedIn', value: (record) => linkedin(record.linkedinUrl), fill: true },
    { label: 'Status', value: (record) => OUTREACH_STATUS[record.status] },
    { label: 'Touches', value: (record) => `${record.touchCount} of 4` },
    { label: 'Next action', value: (record) => date(record.nextActionDate), fill: true },
    { label: 'Owner', value: (record) => record.ownerName, fill: true },
  ],
};

export const COMPANY_MERGE = {
  moves: 'contacts, searches and history',
  fields: [
    { label: 'Name', value: (record) => record.name },
    { label: 'Stage', value: (record) => COMPANY_STATUS[record.status] },
    { label: 'Priority', value: (record) => PRIORITY[record.priority], fill: true },
    { label: 'Industry', value: (record) => INDUSTRY[record.industry], fill: true },
    { label: 'Website', value: (record) => record.website, fill: true },
    { label: 'Location', value: (record) => record.location, fill: true },
    { label: 'Owner', value: (record) => record.ownerName, fill: true },
    { label: 'Follow up', value: (record) => date(record.followUpDate), fill: true },
    { label: 'Contacts', value: (record) => record.contactCount, combine: (kept, other) => kept.contactCount + other.contactCount },
    {
      label: 'Open searches',
      value: (record) => record.openSearchCount,
      combine: (kept, other) => kept.openSearchCount + other.openSearchCount,
    },
  ],
};

export const CANDIDATE_MERGE = {
  moves: 'history, searches, placements and files',
  fields: [
    { label: 'Name', value: (record) => record.name },
    { label: 'Status', value: (record) => CANDIDATE_STATUS[record.status] },
    { label: 'Title', value: (record) => record.currentTitle, fill: true },
    { label: 'Employer', value: (record) => record.currentCompany, fill: true },
    { label: 'Email', value: (record) => record.email, fill: true },
    { label: 'Phone', value: (record) => record.phone, fill: true },
    { label: 'LinkedIn', value: (record) => linkedin(record.linkedinUrl), fill: true },
    { label: 'Location', value: (record) => record.location, fill: true },
    { label: 'Owner', value: (record) => record.ownerName, fill: true },
  ],
};
