// AI for candidates: reading resumes into the record, and call notes into a write-up and changes.
import { CANDIDATE_STATUS, EMPLOYMENT_TYPE, WORK_MODEL } from './labels.js';
import { dollars } from './marketing.js';

export const RESUME_ACCEPT = '.pdf,.docx,.odt,.rtf,.txt';
const RESUME_MAX_BYTES = 10 * 1024 * 1024;
const READABLE_EXTENSIONS = ['pdf', 'docx', 'odt', 'rtf', 'txt'];

/** File types AI can read. Old Word (.doc) files can be stored but not read. */
export const READABLE_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.oasis.opendocument.text',
  'application/rtf',
  'text/plain',
];

/** Why AI can't read this file, or null. */
export function resumeProblem(file) {
  const extension = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : '';
  if (extension === 'doc') return 'Old Word files (.doc) can’t be read. Open it in Word, save it as .docx or PDF, and use that.';
  if (!READABLE_EXTENSIONS.includes(extension)) return 'Use a PDF, Word (.docx), OpenDocument (.odt), RTF or text file.';
  if (file.size > RESUME_MAX_BYTES) return `${file.name} is over 10 MB. Save a smaller copy and use that.`;
  if (file.size === 0) return `${file.name} is empty.`;
  return null;
}

/** "Read by Claude for $0.01". */
export function readBy(provider, cents) {
  return `Read by ${provider} for ${dollars(cents)}`;
}

/** The chosen AI provider and whether it has a key, from the AI status. */
export function chosenProvider(status) {
  return status?.providers.find((provider) => provider.id === status.provider) ?? null;
}

/** What a resume can fill in on a record that already exists. */
const RESUME_FIELDS = [
  ['email', 'Email'],
  ['phone', 'Phone'],
  ['linkedinUrl', 'LinkedIn'],
  ['location', 'Location'],
  ['currentTitle', 'Current title'],
  ['currentCompany', 'Current company'],
  ['track', 'Track'],
  ['roleAreas', 'Roles'],
  ['experience', 'Experience'],
  ['regulatedExperience', 'Regulated experience'],
  ['productionEvidence', 'Production evidence'],
];

function blank(value) {
  return value === null || value === undefined || value === '' || value === false || (Array.isArray(value) && value.length === 0);
}

/** The record with the resume's answers in the fields it leaves blank, and the names of those fields. Nothing is overwritten. */
export function fillBlanks(record, found) {
  const candidate = { ...record };
  const filled = [];
  for (const [key, label] of RESUME_FIELDS) {
    if (blank(record[key]) && !blank(found[key])) {
      candidate[key] = found[key];
      filled.push(label);
    }
  }
  return { candidate, filled };
}

/** What a call can change on a candidate, and how to show each value. */
export const CALL_CHANGES = [
  { key: 'status', label: 'Status', show: (value) => CANDIDATE_STATUS[value] },
  { key: 'currentTitle', label: 'Current title' },
  { key: 'currentCompany', label: 'Current company' },
  { key: 'location', label: 'Location' },
  { key: 'workPreference', label: 'Work preference', show: (value) => WORK_MODEL[value] },
  { key: 'willingToRelocate', label: 'Willing to relocate', show: (value) => (value ? 'Yes' : 'No') },
  { key: 'employmentType', label: 'Employment', show: (value) => EMPLOYMENT_TYPE[value] },
  { key: 'compExpectation', label: 'Compensation' },
];

/** A value of one of those fields in words. */
export function shown(change, value) {
  if (value === null || value === undefined || value === '') return 'Not set';
  return change.show ? change.show(value) : value;
}
