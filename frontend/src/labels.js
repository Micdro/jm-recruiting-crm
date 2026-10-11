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

/** The client stages, in the order a company moves through them (as in ClickUp's client list). */
export const COMPANY_STATUS = {
  LEAD: 'Lead',
  INITIAL_OUTREACH: 'Initial outreach',
  ENGAGED: 'Engaged',
  DISCOVERY_SCHEDULED: 'Discovery scheduled',
  DISCOVERY_COMPLETED: 'Discovery completed',
  CONTRACT_SENT: 'Contract sent',
  CONTRACT_SIGNED: 'Contract signed',
  ONBOARDED: 'Onboarded',
  PLACING: 'Placing',
  LONG_TERM_INTEREST: 'Long-term interest',
  DORMANT: 'Dormant',
  NOT_QUALIFIED: 'Not qualified',
  NOT_INTERESTED: 'Not interested',
  COMPLETE: 'Complete',
};

/** Stages where the company has signed and work is under way or done. */
export const CLIENT_STAGES = ['CONTRACT_SIGNED', 'ONBOARDED', 'PLACING', 'COMPLETE'];
/** Stages that are parked or closed, hidden from the default company list. */
export const PARKED_COMPANY_STAGES = ['DORMANT', 'NOT_QUALIFIED', 'NOT_INTERESTED', 'COMPLETE'];

export const INDUSTRY = {
  BANKS: 'Banks',
  FINANCIAL_SERVICES: 'Financial services',
  INSURANCE: 'Insurance',
  UNCLEAR: 'Unclear',
};

export const SEGMENT = {
  FRAUD_IDENTITY_RISK: 'Fraud, identity and risk',
  DECISIONING: 'Decisioning',
  COMPLIANCE_AML: 'Compliance and AML',
  WORKFLOWS_PLATFORMS: 'Workflows and platforms',
};

export const TIER = {
  PURSUE_NOW: 'Pursue now',
  BUILD_EARLY: 'Build early',
  CLOSELY_MONITOR: 'Closely monitor',
  MONITOR: 'Monitor',
  HOLD: 'Hold',
};

export const PRIORITY = {
  URGENT: 'Urgent',
  HIGH: 'High',
  NORMAL: 'Normal',
  LOW: 'Low',
};

export const HIRING_NEED = {
  FDE: 'Forward deployed engineers',
  AI_PLATFORM: 'AI platform engineers',
  BOTH: 'Both',
  NOT_SURE: 'Not sure yet',
};

export const LEAD_SOURCE = {
  AI_RESEARCH: 'AI research',
  LINKEDIN: 'LinkedIn',
  COLD_CALL: 'Cold call',
  WEBSITE: 'Website',
  INBOUND_CALL: 'Inbound call',
  SOCIAL: 'Social',
  REFERRAL: 'Referral',
  EVENT: 'Event',
  OTHER: 'Other',
};

export const BUYING_ROLE = {
  BUYER: 'Buyer',
  INFLUENCER: 'Influencer',
  TALENT: 'Talent team',
  OTHER: 'Other',
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
  ONE_TO_TWO_MONTHS: '1 to 2 months',
  THREE_PLUS_MONTHS: '3+ months',
  EXPLORATORY: 'Exploratory, building pipeline',
};

export const CANDIDATE_STATUS = {
  ACTIVE: 'Active',
  PASSIVE: 'Passive',
  PLACED: 'Placed',
  NOT_LOOKING: 'Not looking',
  NOT_A_FIT: 'Not a fit',
  DO_NOT_CONTACT: 'Do not contact',
};

export const CANDIDATE_SOURCE = {
  LINKEDIN: 'LinkedIn',
  REFERRAL: 'Referral',
  INBOUND: 'Website or inbound',
  NETWORK: 'Network',
  INDEED: 'Indeed',
  ZIPRECRUITER: 'ZipRecruiter',
  OTHER: 'Other',
};

/** Which part of production AI the candidate covers (ClickUp "Applicable Role(s)"). */
export const ROLE_AREA = {
  DESIGN: 'Design',
  DEPLOY: 'Deploy',
  OPERATE: 'Operate',
  OTHER: 'Other',
};

export const EXPERIENCE = {
  YEARS_1_2: '1 to 2 years',
  YEARS_3_4: '3 to 4 years',
  YEARS_5_7: '5 to 7 years',
  YEARS_8_9: '8 to 9 years',
  YEARS_10_PLUS: '10+ years',
};

export const EMPLOYMENT_TYPE = {
  FULL_TIME: 'Full time',
  CONTRACT: 'Contract only',
  EITHER: 'Full time or contract',
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

  LEAD: 'plain',
  INITIAL_OUTREACH: 'progress',
  DISCOVERY_SCHEDULED: 'warn',
  DISCOVERY_COMPLETED: 'warn',
  CONTRACT_SENT: 'warn',
  CONTRACT_SIGNED: 'good',
  ONBOARDED: 'good',
  PLACING: 'good',
  LONG_TERM_INTEREST: 'muted',
  DORMANT: 'muted',
  NOT_QUALIFIED: 'muted',
  COMPLETE: 'good',
  NOT_A_FIT: 'muted',

  URGENT: 'bad',
  HIGH: 'warn',
  LOW: 'muted',

  PURSUE_NOW: 'good',
  BUILD_EARLY: 'progress',
  CLOSELY_MONITOR: 'warn',
  HOLD: 'muted',

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
