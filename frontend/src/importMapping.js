// Turns spreadsheet columns and free-text values into the fields the API expects.

const normalize = (value) => String(value ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

export const CONTACT_FIELDS = [
  { key: 'companyName', label: 'Company', required: true, match: ['company', 'companyname', 'organization', 'organisation', 'account', 'accountname', 'employer'] },
  { key: 'name', label: 'Full name', match: ['name', 'fullname', 'contactname', 'contact', 'person', 'buyer', 'buyername'] },
  { key: 'firstName', label: 'First name', match: ['firstname', 'first', 'givenname'] },
  { key: 'lastName', label: 'Last name', match: ['lastname', 'last', 'surname', 'familyname'] },
  { key: 'title', label: 'Title', match: ['title', 'jobtitle', 'position', 'role'] },
  { key: 'email', label: 'Email', match: ['email', 'emailaddress', 'workemail', 'businessemail'] },
  { key: 'phone', label: 'Phone', match: ['phone', 'phonenumber', 'mobile', 'cell', 'telephone', 'directdial'] },
  { key: 'linkedinUrl', label: 'LinkedIn URL', match: ['linkedin', 'linkedinurl', 'linkedinprofile', 'profileurl', 'linkedinprofileurl', 'personlinkedinurl'] },
  { key: 'status', label: 'Status', match: ['status', 'outreachstatus', 'leadstatus', 'stage'] },
  { key: 'touchCount', label: 'Touches sent', match: ['touches', 'touchcount', 'touchessent', 'touchnumber', 'touch', 'attempts', 'numberoftouches'] },
  { key: 'lastContactedDate', label: 'Last contacted', match: ['lastcontacted', 'lastcontact', 'lastcontacteddate', 'lasttouch', 'lasttouchdate', 'lastactivity', 'lastactivitydate'] },
  { key: 'nextActionDate', label: 'Next action date', match: ['nextactiondate', 'nextdate', 'followupdate', 'nextfollowup', 'nextfollowupdate', 'duedate', 'nexttouchdate'] },
  { key: 'nextActionType', label: 'Next action type', match: ['nextactiontype', 'nextaction', 'actiontype', 'channel', 'nexttouchtype', 'nextstep'] },
  { key: 'notes', label: 'Notes', match: ['notes', 'note', 'comments', 'comment', 'description'] },
  { key: 'companyWebsite', label: 'Company website', match: ['website', 'companywebsite', 'domain', 'companydomain', 'companyurl'] },
  { key: 'companyLocation', label: 'Company location', match: ['companylocation', 'location', 'hq', 'headquarters', 'city'] },
];

export const CANDIDATE_FIELDS = [
  { key: 'name', label: 'Full name', match: ['name', 'fullname', 'candidatename', 'candidate', 'person'] },
  { key: 'firstName', label: 'First name', match: ['firstname', 'first', 'givenname'] },
  { key: 'lastName', label: 'Last name', match: ['lastname', 'last', 'surname', 'familyname'] },
  { key: 'currentTitle', label: 'Current title', match: ['currenttitle', 'title', 'jobtitle', 'position', 'headline'] },
  { key: 'currentCompany', label: 'Current company', match: ['currentcompany', 'company', 'employer', 'organization', 'organisation'] },
  { key: 'email', label: 'Email', match: ['email', 'emailaddress', 'personalemail'] },
  { key: 'phone', label: 'Phone', match: ['phone', 'phonenumber', 'mobile', 'cell'] },
  { key: 'linkedinUrl', label: 'LinkedIn URL', match: ['linkedin', 'linkedinurl', 'linkedinprofile', 'profileurl'] },
  { key: 'location', label: 'Location', match: ['location', 'city', 'basedin', 'region'] },
  { key: 'track', label: 'Track', match: ['track', 'rolefamily', 'function'] },
  { key: 'status', label: 'Status', match: ['status', 'candidatestatus', 'availability'] },
  { key: 'source', label: 'Source', match: ['source', 'leadsource', 'channel'] },
  { key: 'compExpectation', label: 'Compensation', match: ['compensation', 'comp', 'salary', 'salaryexpectation', 'compexpectation', 'targetcomp'] },
  { key: 'productionEvidence', label: 'Production evidence', match: ['productionevidence', 'evidence', 'experience', 'summary'] },
  { key: 'nextActionDate', label: 'Next action date', match: ['nextactiondate', 'followupdate', 'nextfollowup', 'duedate'] },
  { key: 'nextActionType', label: 'Next action type', match: ['nextactiontype', 'nextaction', 'actiontype'] },
  { key: 'notes', label: 'Notes', match: ['notes', 'note', 'comments'] },
];

/** Picks the CSV column for each field by its header. Each column is used once. */
export function guessMapping(fields, headers) {
  const mapping = {};
  const used = new Set();
  for (const field of fields) {
    const header = headers.find((candidate) => !used.has(candidate) && field.match.includes(normalize(candidate)));
    if (header) {
      mapping[field.key] = header;
      used.add(header);
    }
  }
  return mapping;
}

const OUTREACH_SYNONYMS = {
  NEW: ['new', 'notcontacted', 'notstarted', 'todo', 'open', 'untouched', 'lead', 'queued', 'tocontact'],
  IN_SEQUENCE: ['insequence', 'sequence', 'contacted', 'emailed', 'inprogress', 'outreach', 'attempted', 'attempting', 'followup', 'followingup', 'sent', 'working', 'touched'],
  REPLIED: ['replied', 'responded', 'response', 'reply', 'warm', 'interested'],
  MEETING_BOOKED: ['meeting', 'meetingbooked', 'meetingscheduled', 'callbooked', 'booked', 'scheduled', 'demo', 'callscheduled'],
  ENGAGED: ['engaged', 'client', 'customer', 'won', 'closedwon', 'opportunity', 'activeclient'],
  NURTURE: ['nurture', 'later', 'notnow', 'followuplater', 'future', 'revisit', 'parked', 'onhold'],
  NO_RESPONSE: ['noresponse', 'noreply', 'unresponsive', 'ghosted', 'sequencecomplete', 'exhausted', 'dead', 'cold', 'complete', 'completed'],
  NOT_INTERESTED: ['notinterested', 'declined', 'rejected', 'no', 'lost', 'closedlost', 'disqualified', 'notafit', 'notfit'],
  DO_NOT_CONTACT: ['donotcontact', 'dnc', 'unsubscribe', 'unsubscribed', 'optout', 'optedout', 'bounced', 'invalid'],
};

/** Best guess at the CRM status for a spreadsheet status. Empty means "work it out from touches". */
export function guessOutreachStatus(value) {
  const text = normalize(value);
  if (!text) return '';
  for (const [status, words] of Object.entries(OUTREACH_SYNONYMS)) {
    if (words.includes(text)) return status;
  }
  if (/^touch\d/.test(text) || text.includes('touch')) return 'IN_SEQUENCE';
  if (text.includes('notinterest')) return 'NOT_INTERESTED';
  if (text.includes('donot') || text.includes('dnc')) return 'DO_NOT_CONTACT';
  if (text.includes('meeting') || text.includes('booked')) return 'MEETING_BOOKED';
  if (text.includes('repl') || text.includes('respond')) return 'REPLIED';
  if (text.includes('noresponse') || text.includes('noreply')) return 'NO_RESPONSE';
  if (text.includes('nurture')) return 'NURTURE';
  return '';
}

export function guessActionType(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('linkedin') || text.includes('inmail') || text.includes('connect') || text === 'li') return 'LINKEDIN';
  if (text.includes('mail')) return 'EMAIL';
  if (text.includes('call') || text.includes('phone') || text.includes('dial')) return 'CALL';
  if (text.includes('meet')) return 'MEETING';
  return 'OTHER';
}

export function guessCandidateStatus(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('donot') || text === 'dnc') return 'DO_NOT_CONTACT';
  if (text.includes('placed') || text.includes('hired')) return 'PLACED';
  if (text.includes('notlooking') || text.includes('notinterested')) return 'NOT_LOOKING';
  if (text.includes('passive') || text.includes('open')) return 'PASSIVE';
  if (text.includes('active') || text.includes('looking')) return 'ACTIVE';
  return null;
}

export function guessTrack(value) {
  const text = normalize(value);
  if (!text) return null;
  if (/(lead|vp|director|head|cto|chief|manager)/.test(text)) return 'TECHNICAL_LEADERSHIP';
  if (/(engineer|fde|platform|ml|ai|forward|mlops|llm)/.test(text)) return 'PRODUCTION_AI_ENGINEERING';
  return null;
}

export function guessSource(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('linkedin')) return 'LINKEDIN';
  if (text.includes('refer')) return 'REFERRAL';
  if (text.includes('inbound') || text.includes('website') || text.includes('applied')) return 'INBOUND';
  if (text.includes('network')) return 'NETWORK';
  return 'OTHER';
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

function iso(year, month, day) {
  if (year < 100) year += 2000;
  const date = new Date(year, month - 1, day);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** Reads the date formats spreadsheets export. Returns { value, invalid }. */
export function parseLooseDate(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return { value: null, invalid: false };

  let match = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (match) return wrap(iso(+match[1], +match[2], +match[3]));

  match = text.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})/);
  if (match) return wrap(iso(+match[3], +match[1], +match[2]));

  match = text.match(/^([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})/);
  if (match) {
    const month = MONTHS.indexOf(match[1].slice(0, 3).toLowerCase()) + 1;
    if (month > 0) return wrap(iso(+match[3], month, +match[2]));
  }

  match = text.match(/^(\d{1,2})\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})/);
  if (match) {
    const month = MONTHS.indexOf(match[2].slice(0, 3).toLowerCase()) + 1;
    if (month > 0) return wrap(iso(+match[3], month, +match[1]));
  }

  return { value: null, invalid: true };
}

function wrap(value) {
  return { value, invalid: value === null };
}

export function parseCount(raw) {
  const match = String(raw ?? '').match(/\d+/);
  return match ? Number(match[0]) : null;
}

/** Builds the API row for one spreadsheet row. Collects anything that couldn't be read. */
export function buildRow(kind, source, mapping, statusMap) {
  const read = (key) => {
    const column = mapping[key];
    if (!column) return null;
    const value = String(source[column] ?? '').trim();
    return value === '' ? null : value;
  };
  const problems = [];
  const date = (key, label) => {
    const raw = read(key);
    const parsed = parseLooseDate(raw);
    if (parsed.invalid) problems.push(`${label} "${raw}" isn't a date`);
    return parsed.value;
  };

  if (kind === 'contacts') {
    const rawStatus = read('status');
    return {
      row: {
        companyName: read('companyName'),
        companyWebsite: read('companyWebsite'),
        companyLocation: read('companyLocation'),
        name: read('name'),
        firstName: read('firstName'),
        lastName: read('lastName'),
        title: read('title'),
        email: read('email'),
        phone: read('phone'),
        linkedinUrl: read('linkedinUrl'),
        status: rawStatus ? statusMap[rawStatus] || null : null,
        touchCount: parseCount(read('touchCount')),
        lastContactedDate: date('lastContactedDate', 'Last contacted'),
        nextActionDate: date('nextActionDate', 'Next action date'),
        nextActionType: guessActionType(read('nextActionType')),
        notes: read('notes'),
      },
      problems,
    };
  }

  return {
    row: {
      name: read('name'),
      firstName: read('firstName'),
      lastName: read('lastName'),
      email: read('email'),
      phone: read('phone'),
      linkedinUrl: read('linkedinUrl'),
      location: read('location'),
      currentTitle: read('currentTitle'),
      currentCompany: read('currentCompany'),
      track: guessTrack(read('track')),
      status: guessCandidateStatus(read('status')),
      source: guessSource(read('source')),
      compExpectation: read('compExpectation'),
      productionEvidence: read('productionEvidence'),
      nextActionDate: date('nextActionDate', 'Next action date'),
      nextActionType: guessActionType(read('nextActionType')),
      notes: read('notes'),
    },
    problems,
  };
}

export function templateCsv(kind) {
  if (kind === 'contacts') {
    return [
      'Company,First Name,Last Name,Title,Email,Phone,LinkedIn,Status,Touches,Last Contacted,Next Action Date,Next Action Type,Notes,Website',
      'Ledgerlight AI,Dana,Reyes,VP Engineering,dana@ledgerlight.ai,,linkedin.com/in/danareyes,In sequence,2,2026-10-08,2026-10-14,Email,Met at Money20/20,ledgerlight.ai',
    ].join('\n');
  }
  return [
    'Name,Current Title,Current Company,Email,LinkedIn,Location,Track,Status,Source,Compensation,Production Evidence,Notes',
    'Avery Chen,Staff ML Platform Engineer,Cardwise,avery@example.com,linkedin.com/in/averychen,New York,Engineering,Passive,Referral,$240k base,"Owned model serving for a card issuer",',
  ].join('\n');
}
