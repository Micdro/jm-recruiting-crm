// Writes CRM records to an Excel file in the browser. Loaded only when someone exports.
import writeExcelFile, { getSheetData } from 'write-excel-file/browser';
import {
  ACTION_TYPE,
  ACTIVITY_TYPE,
  BUYING_ROLE,
  CANDIDATE_SOURCE,
  CANDIDATE_STATUS,
  COMPANY_STATUS,
  EMPLOYMENT_TYPE,
  ENGAGEMENT_TYPE,
  EXPERIENCE,
  HIRING_NEED,
  INDUSTRY,
  INVOICE_STATUS,
  LEAD_SOURCE,
  OUTREACH_STATUS,
  PRIORITY,
  ROLE_AREA,
  SEARCH_STATUS,
  SEGMENT,
  STAGE,
  TIER,
  TIMELINE,
  TRACK,
  WORK_MODEL,
} from './labels.js';
import { toIsoDate } from './format.js';

const DATE = 'mmm d, yyyy';
const DATE_TIME = 'mmm d, yyyy h:mm AM/PM';
const MONEY = '"$"#,##0';
const HEADER = { fontWeight: 'bold', backgroundColor: '#E6EEF7', bottomBorderStyle: 'thin', bottomBorderColor: '#004080' };

/** A YYYY-MM-DD date as Excel stores it (a day, no time zone). */
function day(iso) {
  if (!iso) return null;
  const [year, month, date] = iso.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, date));
}

/** A moment, as the time it was on this computer's clock. */
function moment(instant) {
  if (!instant) return null;
  const local = new Date(instant);
  return new Date(
    Date.UTC(local.getFullYear(), local.getMonth(), local.getDate(), local.getHours(), local.getMinutes(), local.getSeconds()),
  );
}

/** The day a record was added, on this computer's calendar. */
const added = (record) => (record.createdAt ? toIsoDate(new Date(record.createdAt)) : null);

const text = (value) => (value === null || value === undefined || value === '' ? null : { value: String(value) });
const label = (map) => (value) => (value ? text(map[value] ?? value) : null);
const labels = (map) => (values) => (values?.length ? text(values.map((value) => map[value] ?? value).join(', ')) : null);
const date = (value) => (value ? { value: day(value), type: Date, format: DATE } : null);
const dateTime = (value) => (value ? { value: moment(value), type: Date, format: DATE_TIME } : null);
const money = (value) => (value === null || value === undefined ? null : { value: Number(value), type: Number, format: MONEY });
const number = (value) => (value === null || value === undefined ? null : { value: Number(value), type: Number });
const yesNo = (value) => ({ value: value ? 'Yes' : 'No' });
const long = (value) => (value ? { value: String(value), wrap: true, alignVertical: 'top' } : null);
/** Fees are kept as percents (25 for 25%). */
const percent = (value) => {
  if (value === null || value === undefined) return null;
  const share = Number(value) / 100;
  return { value: share, type: Number, format: Number.isInteger(Number(value)) ? '0%' : '0.0%' };
};

/** [header, width in characters, field (or a function of the record), how to write it] */
const SHEETS = {
  companies: {
    sheet: 'Companies',
    columns: [
      ['Name', 28, 'name', text],
      ['Status', 18, 'status', label(COMPANY_STATUS)],
      ['Industry', 18, 'industry', label(INDUSTRY)],
      ['Segments', 28, 'segments', labels(SEGMENT)],
      ['Tier', 16, 'tier', label(TIER)],
      ['Priority', 10, 'priority', label(PRIORITY)],
      ['Hiring need', 26, 'hiringNeed', label(HIRING_NEED)],
      ['Hiring timeline', 24, 'hiringTimeline', label(TIMELINE)],
      ['Anticipated hires', 10, 'anticipatedHires', number],
      ['Focus', 40, 'focus', long],
      ['Location', 20, 'location', text],
      ['Size', 12, 'companySize', text],
      ['Website', 28, 'website', text],
      ['LinkedIn', 32, 'linkedinProfile', text],
      ['Lead source', 14, 'leadSource', label(LEAD_SOURCE)],
      ['Owner', 18, 'ownerName', text],
      ['Contacts', 10, 'contactCount', number],
      ['Last contacted', 14, 'lastContactedDate', date],
      ['Follow-up', 14, 'followUpDate', date],
      ['Notes', 50, 'notes', long],
      ['Added', 14, added, date],
    ],
  },
  contacts: {
    sheet: 'Contacts',
    columns: [
      ['Name', 24, 'name', text],
      ['Title', 28, 'title', text],
      ['Company', 26, 'companyName', text],
      ['Email', 30, 'email', text],
      ['Phone', 16, 'phone', text],
      ['LinkedIn', 32, 'linkedinUrl', text],
      ['Buying role', 14, 'buyingRole', label(BUYING_ROLE)],
      ['Status', 16, 'status', label(OUTREACH_STATUS)],
      ['Touches', 9, 'touchCount', number],
      ['Last contacted', 14, 'lastContactedDate', date],
      ['Next action', 12, 'nextActionType', label(ACTION_TYPE)],
      ['Next action date', 14, 'nextActionDate', date],
      ['Owner', 18, 'ownerName', text],
      ['Notes', 50, 'notes', long],
      ['Added', 14, added, date],
    ],
  },
  searches: {
    sheet: 'Searches',
    columns: [
      ['Title', 30, 'title', text],
      ['Company', 26, 'companyName', text],
      ['Status', 12, 'status', label(SEARCH_STATUS)],
      ['Track', 26, 'track', label(TRACK)],
      ['Engagement', 14, 'engagementType', label(ENGAGEMENT_TYPE)],
      ['Fee', 8, 'feePercent', percent],
      ['Base from', 12, 'baseMin', money],
      ['Base to', 12, 'baseMax', money],
      ['Location', 20, 'location', text],
      ['Work model', 12, 'workModel', label(WORK_MODEL)],
      ['Timeline', 24, 'timeline', label(TIMELINE)],
      ['Opened', 14, 'openedDate', date],
      ['Hiring manager', 22, 'hiringManagerName', text],
      ['Owner', 18, 'ownerName', text],
      ['Production outcome', 40, 'productionOutcome', long],
      ['Technical scope', 40, 'technicalScope', long],
      ['Accountability', 40, 'accountability', long],
      ['Regulatory constraints', 40, 'regulatoryConstraints', long],
      ['Notes', 50, 'notes', long],
      ['Job description', 60, 'jobDescription', long],
    ],
  },
  candidates: {
    sheet: 'Candidates',
    columns: [
      ['Name', 24, 'name', text],
      ['Current title', 28, 'currentTitle', text],
      ['Current company', 24, 'currentCompany', text],
      ['Email', 30, 'email', text],
      ['Phone', 16, 'phone', text],
      ['LinkedIn', 32, 'linkedinUrl', text],
      ['Location', 20, 'location', text],
      ['Track', 26, 'track', label(TRACK)],
      ['Status', 14, 'status', label(CANDIDATE_STATUS)],
      ['Source', 16, 'source', label(CANDIDATE_SOURCE)],
      ['Experience', 14, 'experience', label(EXPERIENCE)],
      ['Role areas', 22, 'roleAreas', labels(ROLE_AREA)],
      ['Work preference', 14, 'workPreference', label(WORK_MODEL)],
      ['Employment', 20, 'employmentType', label(EMPLOYMENT_TYPE)],
      ['Will relocate', 12, 'willingToRelocate', yesNo],
      ['Regulated experience', 12, 'regulatedExperience', yesNo],
      ['Comp expectation', 18, 'compExpectation', text],
      ['Resume', 32, 'resumeUrl', text],
      ['Production evidence', 50, 'productionEvidence', long],
      ['Last contacted', 14, 'lastContactedDate', date],
      ['Next action', 12, 'nextActionType', label(ACTION_TYPE)],
      ['Next action date', 14, 'nextActionDate', date],
      ['Owner', 18, 'ownerName', text],
      ['Notes', 50, 'notes', long],
      ['Added', 14, added, date],
    ],
  },
  pipeline: {
    sheet: 'Pipeline',
    columns: [
      ['Search', 30, 'searchTitle', text],
      ['Company', 24, 'companyName', text],
      ['Candidate', 24, 'candidateName', text],
      ['Candidate title', 28, 'candidateTitle', text],
      ['Candidate company', 24, 'candidateCompany', text],
      ['Location', 20, 'candidateLocation', text],
      ['Stage', 14, 'stage', label(STAGE)],
      ['Rationale', 50, 'rationale', long],
      ['Stage changed', 20, 'stageChangedAt', dateTime],
    ],
  },
  placements: {
    sheet: 'Placements',
    columns: [
      ['Candidate', 24, 'candidateName', text],
      ['Company', 24, 'companyName', text],
      ['Search', 30, 'searchTitle', text],
      ['Start date', 14, 'startDate', date],
      ['Base salary', 13, 'baseSalary', money],
      ['Fee', 8, 'feePercent', percent],
      ['Fee amount', 13, 'feeAmount', money],
      ['Invoice', 13, 'invoiceStatus', label(INVOICE_STATUS)],
      ['Invoice number', 14, 'invoiceNumber', text],
      ['Invoiced', 14, 'invoiceDate', date],
      ['Due', 14, 'invoiceDueDate', date],
      ['Billed to', 26, 'billToName', text],
      ['Paid', 14, 'paidDate', date],
      ['Guarantee ends', 14, 'guaranteeEndDate', date],
      ['Notes', 50, 'notes', long],
    ],
  },
  activities: {
    sheet: 'Activity',
    columns: [
      ['When', 20, 'occurredAt', dateTime],
      ['Type', 10, 'type', label(ACTIVITY_TYPE)],
      ['Touch', 8, 'touch', yesNo],
      ['What happened', 60, 'summary', long],
      ['Contact', 22, 'contactName', text],
      ['Company', 24, 'companyName', text],
      ['Candidate', 22, 'candidateName', text],
      ['Search', 28, 'searchTitle', text],
      ['By', 18, 'userName', text],
    ],
  },
};

export const PARTS = Object.keys(SHEETS);

/** One sheet's rows, header first, as the library takes them. */
export function sheetFor(part, records) {
  const { sheet, columns } = SHEETS[part];
  const described = columns.map(([header, width, field, write]) => ({
    header: { value: header, ...HEADER },
    width,
    cell: (record) => write(typeof field === 'function' ? field(record) : record[field]),
  }));
  return {
    data: getSheetData(records, described),
    sheet,
    columns: described.map(({ width }) => ({ width })),
    stickyRowsCount: 1,
  };
}

/**
 * Downloads an .xlsx file with a sheet for each list in {@code lists} ({ contacts: [...] }), in the
 * order of PARTS. Lists that are missing or empty still get a sheet with just the headers.
 */
export async function downloadWorkbook(fileName, lists) {
  const sheets = PARTS.filter((part) => lists[part]).map((part) => sheetFor(part, lists[part]));
  await writeExcelFile(sheets, { fontFamily: 'Calibri', fontSize: 11 }).toFile(fileName);
}
