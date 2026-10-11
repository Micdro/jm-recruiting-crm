// Turns spreadsheet columns and free-text values into the fields the API expects.

const normalize = (value) => String(value ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

/**
 * ClickUp exports custom fields with their type, as in "Next Action Data (date)" or
 * "Company (short text)". The type is dropped before matching column names.
 */
export function headerKey(header) {
  return normalize(String(header ?? '').replace(/\s*\([^()]*\)\s*$/, ''));
}

export const CONTACT_FIELDS = [
  { key: 'companyName', label: 'Company', required: true, match: ['company', 'companyname', 'organization', 'organisation', 'account', 'accountname', 'employer', 'clientcompany', 'client'] },
  { key: 'name', label: 'Full name', match: ['name', 'fullname', 'contactname', 'contact', 'person', 'buyer', 'buyername', 'taskname'] },
  { key: 'firstName', label: 'First name', match: ['firstname', 'first', 'givenname', 'buyerfirstname'] },
  { key: 'lastName', label: 'Last name', match: ['lastname', 'last', 'surname', 'familyname', 'buyerlastname'] },
  { key: 'title', label: 'Title', match: ['title', 'jobtitle', 'position', 'buyertitle'] },
  { key: 'buyingRole', label: 'Buying role', match: ['buyerrole', 'buyingrole', 'role', 'decisionrole', 'persona'] },
  { key: 'email', label: 'Email', match: ['email', 'emailaddress', 'workemail', 'businessemail', 'buyeremail'] },
  { key: 'phone', label: 'Phone', match: ['phone', 'phonenumber', 'mobile', 'cell', 'telephone', 'directdial', 'buyerphone'] },
  { key: 'linkedinUrl', label: 'LinkedIn URL', match: ['linkedin', 'linkedinurl', 'linkedinprofile', 'profileurl', 'linkedinprofileurl', 'personlinkedinurl', 'buyerlinkedin'] },
  { key: 'status', label: 'Status', match: ['status', 'outreachstatus', 'leadstatus', 'stage'] },
  { key: 'owner', label: 'Owner', match: ['owner', 'assignee', 'assignees', 'assignedto', 'rep', 'recruiter'] },
  { key: 'touchCount', label: 'Touches sent', match: ['touches', 'touchcount', 'touchessent', 'touchnumber', 'touch', 'attempts', 'numberoftouches'] },
  { key: 'lastContactedDate', label: 'Last contacted', match: ['lastcontacted', 'lastcontact', 'lastcontacteddate', 'lasttouch', 'lasttouchdate', 'lastactivity', 'lastactivitydate'] },
  // ClickUp's outbound list calls this "Next Action Data".
  { key: 'nextActionDate', label: 'Next action date', match: ['nextactiondate', 'nextactiondata', 'nextdate', 'followupdate', 'nextfollowup', 'nextfollowupdate', 'duedate', 'nexttouchdate'] },
  { key: 'nextActionType', label: 'Next action type', match: ['nextactiontype', 'nextaction', 'actiontype', 'channel', 'nexttouchtype', 'nextstep'] },
  { key: 'notes', label: 'Notes', match: ['notes', 'note', 'buyernotes', 'comments', 'comment'] },
  { key: 'notesExtra', label: 'More notes', match: ['communicationnotes', 'internalnotes', 'taskcontent', 'description', 'random'] },
  { key: 'companyWebsite', label: 'Company website', match: ['website', 'companywebsite', 'domain', 'companydomain', 'companyurl'] },
  { key: 'companyLocation', label: 'Company location', match: ['companylocation', 'location', 'hq', 'headquarters', 'city'] },
];

/** The ClickUp client list, or any list of companies. */
export const COMPANY_FIELDS = [
  { key: 'name', label: 'Company name', required: true, match: ['taskname', 'name', 'company', 'companyname', 'client', 'clientname', 'account', 'accountname', 'organization', 'organisation'] },
  { key: 'status', label: 'Stage', match: ['status', 'stage', 'clientstatus', 'clientstage'] },
  { key: 'owner', label: 'Owner', match: ['assignees', 'assignee', 'owner', 'assignedto', 'lead'] },
  { key: 'priority', label: 'Priority', match: ['priority'] },
  { key: 'tier', label: 'Tier', match: ['tier', 'accounttier', 'prioritytier'] },
  { key: 'industry', label: 'Industry', match: ['industry', 'sector', 'vertical'] },
  { key: 'segments', label: 'Segments', match: ['subtype', 'segment', 'segments', 'focusarea', 'category', 'subindustry'] },
  { key: 'focus', label: 'Focus', match: ['focus', 'whattheydo', 'product', 'productfocus', 'offering'] },
  { key: 'hiringNeed', label: 'Hiring need', match: ['hiringrole', 'hiringroles', 'hiringneed', 'rolesneeded', 'roles'] },
  { key: 'hiringTimeline', label: 'Hiring timeline', match: ['hiringtimeline', 'timeline'] },
  { key: 'anticipatedHires', label: 'Anticipated hires', match: ['anticipatedofhires', 'anticipatedhires', 'anticipatednumberofhires', 'numberofhires', 'hires'] },
  { key: 'leadSource', label: 'Lead source', match: ['clientleadsource', 'leadsource', 'source'] },
  { key: 'followUpDate', label: 'Follow-up date', match: ['followupdate', 'followup', 'nextfollowup', 'nextfollowupdate', 'duedate'] },
  { key: 'lastContactedDate', label: 'Last contacted', match: ['lastcontacted', 'lastcontacteddate', 'lastcontact'] },
  { key: 'companySize', label: 'Company size', match: ['companysize', 'size', 'employees', 'headcount'] },
  { key: 'location', label: 'Location', match: ['locationpresence', 'location', 'hq', 'headquarters', 'presence'] },
  { key: 'website', label: 'Website', match: ['website', 'companywebsite', 'domain', 'url'] },
  { key: 'linkedinProfile', label: 'LinkedIn page', match: ['linkedin', 'linkedinurl', 'companylinkedin', 'linkedinpage'] },
  { key: 'notes', label: 'Notes', match: ['clientnotes', 'notes', 'note', 'comments'] },
  { key: 'notesExtra', label: 'More notes', match: ['internalnotes', 'taskcontent', 'description'] },
];

export const CANDIDATE_FIELDS = [
  { key: 'name', label: 'Full name', match: ['name', 'fullname', 'candidatename', 'candidate', 'person', 'taskname'] },
  { key: 'firstName', label: 'First name', match: ['firstname', 'first', 'givenname'] },
  { key: 'lastName', label: 'Last name', match: ['lastname', 'last', 'surname', 'familyname'] },
  { key: 'currentTitle', label: 'Current title', match: ['currenttitle', 'title', 'jobtitle', 'position', 'headline'] },
  { key: 'currentCompany', label: 'Current company', match: ['currentcompany', 'company', 'employer', 'organization', 'organisation'] },
  { key: 'email', label: 'Email', match: ['email', 'emailaddress', 'personalemail'] },
  { key: 'phone', label: 'Phone', match: ['phone', 'phonenumber', 'mobile', 'cell'] },
  { key: 'linkedinUrl', label: 'LinkedIn URL', match: ['linkedin', 'linkedinurl', 'linkedinprofile', 'profileurl'] },
  { key: 'location', label: 'Location', match: ['location', 'city', 'basedin', 'region'] },
  { key: 'track', label: 'Track', match: ['track', 'rolefamily', 'function'] },
  { key: 'status', label: 'Status', match: ['status', 'candidatestatus', 'availability', 'stage'] },
  { key: 'owner', label: 'Owner', match: ['assignees', 'assignee', 'owner', 'assignedto', 'recruiter'] },
  { key: 'source', label: 'Source', match: ['candidatesource', 'source', 'leadsource', 'channel'] },
  { key: 'roleAreas', label: 'Applicable roles', match: ['applicableroles', 'applicablerole', 'roles', 'rolearea', 'roleareas'] },
  { key: 'workPreference', label: 'Work preference', match: ['worktypepreference', 'workpreference', 'worktype', 'remotepreference', 'workmodel'] },
  { key: 'experience', label: 'Years of experience', match: ['yearsofexperience', 'experience', 'years', 'yoe'] },
  { key: 'willingToRelocate', label: 'Willing to relocate', match: ['willingtorelocate', 'relocate', 'relocation', 'opentorelocation'] },
  { key: 'employmentType', label: 'Employment type', match: ['employmenttype', 'jobtype', 'fulltimeorcontract', 'contracttype'] },
  { key: 'tags', label: 'Tags', match: ['tags', 'tag', 'labels'] },
  { key: 'compExpectation', label: 'Compensation', match: ['compensation', 'comp', 'salary', 'salaryexpectation', 'compexpectation', 'targetcomp', 'desiredsalary'] },
  { key: 'productionEvidence', label: 'Production evidence', match: ['productionevidence', 'evidence', 'summary'] },
  { key: 'nextActionDate', label: 'Next action date', match: ['nextactiondate', 'nextactiondata', 'followupdate', 'nextfollowup', 'duedate'] },
  { key: 'nextActionType', label: 'Next action type', match: ['nextactiontype', 'nextaction', 'actiontype'] },
  { key: 'notes', label: 'Notes', match: ['notes', 'note', 'comments', 'candidatenotes'] },
  { key: 'notesExtra', label: 'More notes', match: ['internalnotes', 'taskcontent', 'description'] },
];

export const IMPORT_FIELDS = { contacts: CONTACT_FIELDS, companies: COMPANY_FIELDS, candidates: CANDIDATE_FIELDS };

/** Picks the CSV column for each field by its header. Each column is used once. */
export function guessMapping(fields, headers) {
  const mapping = {};
  const used = new Set();
  for (const field of fields) {
    const header = headers.find((candidate) => !used.has(candidate) && field.match.includes(headerKey(candidate)));
    if (header) {
      mapping[field.key] = header;
      used.add(header);
    }
  }
  return mapping;
}

const OUTREACH_SYNONYMS = {
  NEW: ['new', 'notcontacted', 'notstarted', 'todo', 'open', 'untouched', 'lead', 'queued', 'tocontact', 'notyetcontacted'],
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
  // ClickUp candidate list: dq, then offer accepted, onboarding and placed all mean placed.
  if (text === 'dq' || text.includes('disqualif') || text.includes('notafit') || text.includes('rejected')) return 'NOT_A_FIT';
  if (text.includes('placed') || text.includes('hired') || text.includes('offeraccepted') || text.includes('onboarding')) return 'PLACED';
  if (['initialinquiry', 'candidate', 'interviewstage', 'interviewing', 'offerextended', 'submitted'].includes(text)) return 'ACTIVE';
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
  if (text.includes('indeed')) return 'INDEED';
  if (text.includes('ziprecruiter') || text.includes('zip')) return 'ZIPRECRUITER';
  if (text.includes('inbound') || text.includes('website') || text.includes('applied')) return 'INBOUND';
  if (text.includes('network')) return 'NETWORK';
  return 'OTHER';
}

/** ClickUp's outbound sequence: LinkedIn, Email, 2nd LinkedIn, 2nd Email. The next one tells how many went out. */
export function touchesBefore(nextActionType) {
  const text = normalize(nextActionType);
  if (!text) return null;
  const second = /^(2nd|second|2)/.test(text);
  if (text.includes('linkedin')) return second ? 2 : 0;
  if (text.includes('mail')) return second ? 3 : 1;
  return null;
}

const COMPANY_STATUS_SYNONYMS = {
  LEAD: ['lead', 'target', 'new', 'prospect'],
  INITIAL_OUTREACH: ['initialoutreach', 'outreach', 'prospecting', 'contacted'],
  ENGAGED: ['engaged', 'inconversation', 'replied', 'warm'],
  DISCOVERY_SCHEDULED: ['discoveryscheduled', 'callbooked', 'meetingbooked'],
  DISCOVERY_COMPLETED: ['discoverycompleted', 'discoverydone'],
  CONTRACT_SENT: ['contractsent', 'proposalsent'],
  CONTRACT_SIGNED: ['contractsigned', 'signed'],
  ONBOARDED: ['onboarded', 'onboarding'],
  PLACING: ['placing', 'client', 'active', 'activeclient', 'searching'],
  LONG_TERM_INTEREST: ['longterminterest', 'longterm', 'nurture', 'later'],
  DORMANT: ['dormant', 'inactive', 'paused', 'onhold'],
  NOT_QUALIFIED: ['notqualified', 'notafit', 'disqualified', 'dq'],
  NOT_INTERESTED: ['notinterested', 'declined', 'lost', 'closedlost'],
  COMPLETE: ['complete', 'completed', 'done', 'closed', 'closedwon'],
};

/** Best guess at the client stage for a spreadsheet stage, such as ClickUp's "discovery scheduled". */
export function guessCompanyStatus(value) {
  const text = normalize(value);
  if (!text) return '';
  for (const [status, words] of Object.entries(COMPANY_STATUS_SYNONYMS)) {
    if (words.includes(text)) return status;
  }
  return '';
}

/** The status translation step, per kind of import. */
export const STATUS_GUESSERS = {
  contacts: guessOutreachStatus,
  companies: guessCompanyStatus,
  candidates: (value) => guessCandidateStatus(value) ?? '',
};

export function guessPriority(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('urgent')) return 'URGENT';
  if (text.includes('high')) return 'HIGH';
  if (text.includes('low')) return 'LOW';
  if (text.includes('normal') || text.includes('medium')) return 'NORMAL';
  return null;
}

export function guessTier(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('pursue')) return 'PURSUE_NOW';
  if (text.includes('build')) return 'BUILD_EARLY';
  if (text.includes('closely')) return 'CLOSELY_MONITOR';
  if (text.includes('monitor')) return 'MONITOR';
  if (text.includes('hold')) return 'HOLD';
  return null;
}

export function guessIndustry(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('insur')) return 'INSURANCE';
  if (text.includes('bank')) return 'BANKS';
  if (text.includes('financ') || text.includes('fintech') || text.includes('lend') || text.includes('payment')) return 'FINANCIAL_SERVICES';
  return 'UNCLEAR';
}

/** Splits a multi-value cell ("Fraud / Identity, AML") into the segments it names. */
export function guessSegments(value) {
  const text = normalize(value);
  if (!text) return [];
  const segments = [];
  if (/fraud|identity|risk|kyc/.test(text)) segments.push('FRAUD_IDENTITY_RISK');
  if (/decision|credit|underwrit/.test(text)) segments.push('DECISIONING');
  if (/compliance|aml|regtech|sanction/.test(text)) segments.push('COMPLIANCE_AML');
  if (/workflow|platform|infra|ops/.test(text)) segments.push('WORKFLOWS_PLATFORMS');
  return segments;
}

export function guessHiringNeed(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('both') || (/fde|forward/.test(text) && text.includes('platform'))) return 'BOTH';
  if (/fde|forward/.test(text)) return 'FDE';
  if (text.includes('platform')) return 'AI_PLATFORM';
  return 'NOT_SURE';
}

export function guessTimeline(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.startsWith('asap') || text.includes('30days') || text.includes('immediate')) return 'ASAP';
  if (text.includes('explor') || text.includes('pipeline')) return 'EXPLORATORY';
  if (/^12month|^1to2|60days/.test(text)) return 'ONE_TO_TWO_MONTHS';
  if (/^3|90days|later/.test(text)) return 'THREE_PLUS_MONTHS';
  return null;
}

export function guessLeadSource(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('ai') && text.includes('research')) return 'AI_RESEARCH';
  if (text.includes('linkedin')) return 'LINKEDIN';
  if (text.includes('coldcall')) return 'COLD_CALL';
  if (text.includes('inbound') && text.includes('call')) return 'INBOUND_CALL';
  if (text.includes('website') || text.includes('web')) return 'WEBSITE';
  if (text.includes('social') || text.includes('twitter') || text.includes('x.com')) return 'SOCIAL';
  if (text.includes('refer')) return 'REFERRAL';
  if (text.includes('event') || text.includes('conference')) return 'EVENT';
  return 'OTHER';
}

export function guessBuyingRole(value) {
  const text = normalize(value);
  if (!text) return null;
  if (/buyer|decision|economic|hiringmanager|signer|budget/.test(text)) return 'BUYER';
  if (/influenc|champion|technical|evaluator/.test(text)) return 'INFLUENCER';
  if (/talent|recruit|hr|people|ta$/.test(text)) return 'TALENT';
  return 'OTHER';
}

export function guessRoleAreas(value) {
  const text = normalize(value);
  if (!text) return [];
  const areas = ['DESIGN', 'DEPLOY', 'OPERATE'].filter((area) => text.includes(area.toLowerCase()));
  if (text.includes('other')) areas.push('OTHER');
  return areas;
}

export function guessWorkModel(value) {
  const text = normalize(value);
  if (!text) return null;
  if (text.includes('hybrid')) return 'HYBRID';
  if (text.includes('remote')) return 'REMOTE';
  if (text.includes('onsite') || text.includes('office') || text.includes('inperson')) return 'ONSITE';
  return null;
}

export function guessExperience(value) {
  const match = String(value ?? '').match(/\d+/);
  if (!match) return null;
  const years = Number(match[0]);
  if (years <= 2) return 'YEARS_1_2';
  if (years <= 4) return 'YEARS_3_4';
  if (years <= 7) return 'YEARS_5_7';
  if (years <= 9) return 'YEARS_8_9';
  return 'YEARS_10_PLUS';
}

export function guessEmploymentType(value) {
  const text = normalize(value);
  if (!text) return null;
  const contract = text.includes('contract');
  const fullTime = text.includes('fulltime') || text.includes('permanent') || text.includes('fte');
  if (text.includes('either') || text.includes('both') || (contract && fullTime && !text.includes('only'))) return 'EITHER';
  if (contract) return 'CONTRACT';
  if (fullTime) return 'FULL_TIME';
  return null;
}

export function parseYesNo(value) {
  const text = normalize(value);
  if (!text) return null;
  if (['yes', 'y', 'true', '1', 'checked', 'willing', 'open'].includes(text)) return true;
  if (['no', 'n', 'false', '0', 'unchecked', 'notwilling'].includes(text)) return false;
  return null;
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
  // ClickUp writes dates like "Friday, October 10th 2025" or as a timestamp in milliseconds.
  const text = String(raw ?? '')
    .trim()
    .replace(/^(mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\s+/i, '')
    .replace(/(\d{1,2})(st|nd|rd|th)\b/i, '$1');
  if (!text) return { value: null, invalid: false };

  if (/^\d{12,13}$/.test(text)) {
    const date = new Date(Number(text));
    return wrap(iso(date.getFullYear(), date.getMonth() + 1, date.getDate()));
  }

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

function joinNotes(...parts) {
  const kept = parts.filter(Boolean);
  return kept.length ? kept.join('\n\n') : null;
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
  const rawStatus = read('status');
  const status = rawStatus ? statusMap[rawStatus] || null : null;
  const notes = joinNotes(read('notes'), read('notesExtra'));

  if (kind === 'contacts') {
    const rawNextType = read('nextActionType');
    // Without a touch count, ClickUp's next action ("2nd Email") says how far the sequence got.
    const touchCount = parseCount(read('touchCount')) ?? touchesBefore(rawNextType);
    return {
      row: {
        companyName: read('companyName'),
        companyWebsite: read('companyWebsite'),
        companyLocation: read('companyLocation'),
        name: read('name'),
        firstName: read('firstName'),
        lastName: read('lastName'),
        title: read('title'),
        buyingRole: guessBuyingRole(read('buyingRole')),
        email: read('email'),
        phone: read('phone'),
        linkedinUrl: read('linkedinUrl'),
        status,
        owner: read('owner'),
        touchCount,
        lastContactedDate: date('lastContactedDate', 'Last contacted'),
        nextActionDate: date('nextActionDate', 'Next action date'),
        nextActionType: guessActionType(rawNextType),
        notes,
      },
      problems,
    };
  }

  if (kind === 'companies') {
    return {
      row: {
        name: read('name'),
        status,
        owner: read('owner'),
        priority: guessPriority(read('priority')),
        tier: guessTier(read('tier')),
        industry: guessIndustry(read('industry')),
        segments: guessSegments(read('segments')),
        focus: read('focus'),
        hiringNeed: guessHiringNeed(read('hiringNeed')),
        hiringTimeline: guessTimeline(read('hiringTimeline')),
        anticipatedHires: parseCount(read('anticipatedHires')),
        leadSource: guessLeadSource(read('leadSource')),
        followUpDate: date('followUpDate', 'Follow-up date'),
        lastContactedDate: date('lastContactedDate', 'Last contacted'),
        companySize: read('companySize'),
        location: read('location'),
        website: read('website'),
        linkedinProfile: read('linkedinProfile'),
        notes,
      },
      problems,
    };
  }

  const tags = read('tags');
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
      status,
      owner: read('owner'),
      source: guessSource(read('source')),
      roleAreas: guessRoleAreas(read('roleAreas')),
      // ClickUp tags such as "full time", "contract only", "remote" fill these when there's no column.
      workPreference: guessWorkModel(read('workPreference')) ?? guessWorkModel(tags),
      experience: guessExperience(read('experience')),
      willingToRelocate: parseYesNo(read('willingToRelocate')),
      employmentType: guessEmploymentType(read('employmentType')) ?? guessEmploymentType(tags),
      compExpectation: read('compExpectation'),
      productionEvidence: read('productionEvidence'),
      nextActionDate: date('nextActionDate', 'Next action date'),
      nextActionType: guessActionType(read('nextActionType')),
      notes,
    },
    problems,
  };
}

const TEMPLATES = {
  contacts: [
    'Task Name,Status,Company,Title,Email,LinkedIn,Owner,Next Action Data,Next Action Type,Last Contacted Date,Notes',
    'Dana Reyes,contacted,Ledgerlight AI,VP Engineering,dana@ledgerlight.ai,linkedin.com/in/danareyes,MD,2026-10-14,2nd Linkedin,2026-10-08,Met at Money20/20',
  ],
  companies: [
    'Task Name,Status,Priority,Assignees,Industry,Subtype,Hiring Role,Hiring Timeline,Anticipated # of Hires,Client Lead Source,Follow-Up Date,Location/Presence,Client Notes,Internal Notes',
    'Ledgerlight AI,discovery scheduled,high,Michael,Banks,Fraud / Identity / Risk,FDE,1-2 months,2,LinkedIn,2026-10-20,New York,"Two FDEs for bank pilots","Warm intro from Jane"',
  ],
  candidates: [
    'Task Name,Status,Candidate Source,Applicable Role(s),Work Type Preference,Years of Experience,Willing to Relocate,Tags,Email,LinkedIn,Location,Notes',
    'Avery Chen,candidate,Referral,"Deploy, Operate",Hybrid,5-7,Yes,full time,avery@example.com,linkedin.com/in/averychen,New York,"Owned model serving for a card issuer"',
  ],
};

/** A small CSV in ClickUp's column names, for people starting from a spreadsheet instead. */
export function templateCsv(kind) {
  return TEMPLATES[kind].join('\n');
}
