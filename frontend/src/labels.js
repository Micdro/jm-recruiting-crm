// Display names and colors for every fixed choice in the CRM.
// Keys match the enum names the API sends and accepts.

export const MAX_TOUCHES = 4;

export const OUTREACH_STATUS = {
  NEW: 'New',
  IN_SEQUENCE: 'In sequence',
  REPLIED: 'Replied',
  MEETING_BOOKED: 'Meeting booked',
  ENGAGED: 'Engaged',
  NURTURE: 'Nurture',
  NO_RESPONSE: 'No response',
  NOT_INTERESTED: 'Not interested',
  DO_NOT_CONTACT: 'Do not contact',
};

export const COMPANY_STATUS = {
  TARGET: 'Target',
  PROSPECTING: 'Prospecting',
  IN_CONVERSATION: 'In conversation',
  CLIENT: 'Client',
  NOT_A_FIT: 'Not a fit',
};

export const ACTION_TYPE = {
  EMAIL: 'Email',
  LINKEDIN: 'LinkedIn',
  CALL: 'Call',
  MEETING: 'Meeting',
  OTHER: 'Other',
};

export const ACTIVITY_TYPE = {
  EMAIL: 'Email',
  LINKEDIN: 'LinkedIn',
  CALL: 'Call',
  MEETING: 'Meeting',
  REPLY: 'Reply',
  NOTE: 'Note',
  STATUS_CHANGE: 'Status',
  STAGE_CHANGE: 'Stage',
};

/** What a person can log by hand, in the order they appear in the log form. */
export const LOGGABLE_TYPES = ['EMAIL', 'LINKEDIN', 'CALL', 'REPLY', 'MEETING', 'NOTE'];
export const OUTREACH_TYPES = ['EMAIL', 'LINKEDIN', 'CALL'];

export const TRACK = {
  PRODUCTION_AI_ENGINEERING: 'Production AI engineering',
  TECHNICAL_LEADERSHIP: 'Technical leadership',
};

export const SEARCH_STATUS = {
  INTAKE: 'Intake',
  ACTIVE: 'Active',
  ON_HOLD: 'On hold',
  FILLED: 'Filled',
  CANCELLED: 'Cancelled',
};

export const OPEN_SEARCH_STATUSES = ['INTAKE', 'ACTIVE', 'ON_HOLD'];

export const ENGAGEMENT_TYPE = {
  CONTINGENCY: 'Contingency',
  EXCLUSIVE: 'Exclusive',
  RETAINED: 'Retained',
};

export const WORK_MODEL = {
  REMOTE: 'Remote',
  HYBRID: 'Hybrid',
  ONSITE: 'On-site',
};

export const TIMELINE = {
  ASAP: 'ASAP (within 30 days)',
  WITHIN_60_DAYS: 'Within 60 days',
  WITHIN_90_DAYS: 'Within 90 days',
  EXPLORATORY: 'Exploratory pipeline',
};

export const CANDIDATE_STATUS = {
  ACTIVE: 'Active',
  PASSIVE: 'Passive',
  PLACED: 'Placed',
  NOT_LOOKING: 'Not looking',
  DO_NOT_CONTACT: 'Do not contact',
};

export const CANDIDATE_SOURCE = {
  LINKEDIN: 'LinkedIn',
  REFERRAL: 'Referral',
  INBOUND: 'Inbound',
  NETWORK: 'Network',
  OTHER: 'Other',
};

export const STAGE = {
  SOURCED: 'Sourced',
  CONTACTED: 'Contacted',
  ASSESSING: 'Assessing',
  SHORTLISTED: 'Shortlisted',
  SUBMITTED: 'Submitted',
  INTERVIEWING: 'Interviewing',
  OFFER: 'Offer',
  PLACED: 'Placed',
  CLIENT_PASSED: 'Client passed',
  WITHDREW: 'Withdrew',
};

/** Stages shown as board columns, in process order. */
export const BOARD_STAGES = [
  'SOURCED',
  'CONTACTED',
  'ASSESSING',
  'SHORTLISTED',
  'SUBMITTED',
  'INTERVIEWING',
  'OFFER',
  'PLACED',
];
export const CLOSED_STAGES = ['CLIENT_PASSED', 'WITHDREW'];
/** Stages where the client sees the candidate, so a rationale should exist. */
export const SHORTLIST_STAGES = ['SHORTLISTED', 'SUBMITTED', 'INTERVIEWING', 'OFFER', 'PLACED'];

export const INVOICE_STATUS = {
  NOT_INVOICED: 'Not invoiced',
  INVOICED: 'Invoiced',
  PAID: 'Paid',
};

export const ROLE = {
  ADMIN: 'Admin',
  MEMBER: 'Member',
};

export function options(map) {
  return Object.entries(map).map(([value, label]) => ({ value, label }));
}

// Pill tones: progress (moving), good (won), warn (needs attention), muted (parked), bad (stop).
const TONES = {
  NEW: 'plain',
  IN_SEQUENCE: 'progress',
  REPLIED: 'warn',
  MEETING_BOOKED: 'good',
  ENGAGED: 'good',
  NURTURE: 'muted',
  NO_RESPONSE: 'muted',
  NOT_INTERESTED: 'muted',
  DO_NOT_CONTACT: 'bad',

  TARGET: 'plain',
  PROSPECTING: 'progress',
  IN_CONVERSATION: 'warn',
  CLIENT: 'good',
  NOT_A_FIT: 'muted',

  INTAKE: 'warn',
  ACTIVE: 'progress',
  ON_HOLD: 'muted',
  FILLED: 'good',
  CANCELLED: 'muted',

  PASSIVE: 'plain',
  PLACED: 'good',
  NOT_LOOKING: 'muted',

  NOT_INVOICED: 'warn',
  INVOICED: 'progress',
  PAID: 'good',
};

export function toneFor(value) {
  return TONES[value] ?? 'plain';
}
