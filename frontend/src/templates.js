import { MAX_TOUCHES } from './labels.js';

/** The channel for each touch of the sequence. The firm reaches out on LinkedIn only. */
const SEQUENCE = ['LINKEDIN', 'LINKEDIN', 'LINKEDIN', 'LINKEDIN'];

/** What each touch of the sequence is. */
export const TOUCH_NAMES = ['Connection request', 'First message', 'Follow-up', 'Last message'];

/** LinkedIn cuts connection request notes off at this many characters. */
export const LINKEDIN_NOTE_LIMIT = 300;

/** The words people can put in braces, and where each comes from. */
export const PLACEHOLDERS = [
  { key: 'first name', label: 'Their first name', value: (contact) => firstName(contact?.name) },
  { key: 'name', label: 'Their full name', value: (contact) => contact?.name },
  { key: 'title', label: 'Their title', value: (contact) => contact?.title },
  { key: 'company', label: 'Their company', value: (contact) => contact?.companyName },
  // Filled in when writing from a hiring signal: the open role's title.
  { key: 'role', label: 'The open role', value: (contact) => contact?.role },
  { key: 'my first name', label: 'Your first name', value: (contact, me) => firstName(me?.displayName) },
  { key: 'my name', label: 'Your full name', value: (contact, me) => me?.displayName },
];

const PATTERN = new RegExp(`\\{(${PLACEHOLDERS.map((placeholder) => placeholder.key).join('|')})\\}`, 'gi');

function firstName(name) {
  return name ? name.trim().split(/\s+/)[0] : '';
}

/** The touch a contact is on: the next one in the sequence, 1 to 4. */
export function nextTouch(contact) {
  return Math.min((contact?.touchCount ?? 0) + 1, MAX_TOUCHES);
}

export function channelForTouch(touch) {
  return SEQUENCE[Math.max(0, Math.min(touch, MAX_TOUCHES) - 1)];
}

/**
 * Fills a template's placeholders from a contact and the signed-in person. A placeholder with
 * nothing to fill it (a contact without a title, say) stays as it is, so it's easy to spot.
 */
export function fill(text, contact, me) {
  return (text ?? '').replace(PATTERN, (match, key) => {
    const placeholder = PLACEHOLDERS.find((candidate) => candidate.key === key.toLowerCase());
    return placeholder.value(contact, me) || match;
  });
}

/** Placeholders still in a text (nothing filled them in), as plain words. */
export function leftovers(text) {
  const found = new Set();
  for (const match of (text ?? '').matchAll(PATTERN)) {
    found.add(PLACEHOLDERS.find((placeholder) => placeholder.key === match[1].toLowerCase()).label.toLowerCase());
  }
  return [...found];
}

/**
 * The best template for a touch: one written for that touch and channel (your own first), then
 * one for any touch on that channel.
 */
export function bestTemplate(templates, channel, touch, meId) {
  const onChannel = templates.filter((template) => template.channel === channel);
  const mineFirst = (list) => [...list.filter((template) => template.ownerId === meId), ...list.filter((template) => template.ownerId !== meId)];
  return (
    mineFirst(onChannel.filter((template) => template.touch === touch))[0] ??
    mineFirst(onChannel.filter((template) => template.touch == null))[0] ??
    onChannel[0] ??
    null
  );
}

/**
 * The message to start from when writing about an open role: a saved template that uses {role}
 * (written for this touch first, your own first), or null to use the built-in signal message.
 */
export function signalTemplate(templates, channel, touch, meId) {
  const withRole = (templates ?? []).filter((template) => template.channel === channel && /\{role\}/i.test(template.body ?? ''));
  return bestTemplate(withRole, channel, touch, meId);
}

/**
 * Built-in messages about an open role, used until a template with {role} is saved. The first
 * touch is a connection request, so it stays under LinkedIn's note limit.
 */
export function signalDraft(channel, touch, cold) {
  if (channel === 'EMAIL') {
    return {
      name: 'Hiring signal message',
      subject: 'Your {role} opening',
      body: [
        'Hi {first name},',
        '',
        'I saw the {role} opening at {company}. We recruit forward deployed and AI platform engineers who have ' +
          'shipped production AI at banks and insurers, and I have people who fit this role.',
        '',
        'Would a 15-minute call this week work?',
        '',
        '{my first name}',
      ].join('\n'),
    };
  }
  if (cold && touch === 1) {
    return {
      name: 'Hiring signal message',
      subject: null,
      body:
        'Hi {first name}, I saw the {role} opening at {company}. I recruit forward deployed and AI platform ' +
        'engineers for vendors selling into banks and insurers, and know people who fit. Open to connecting?',
    };
  }
  return {
    name: 'Hiring signal message',
    subject: null,
    body: [
      'Hi {first name}, I saw the {role} opening at {company}.',
      '',
      'We recruit forward deployed and AI platform engineers who have shipped production AI under bank-grade ' +
        'security and compliance, and I have candidates who fit this role.',
      '',
      'Would a 15-minute call this week work?',
      '',
      '{my first name}',
    ].join('\n'),
  };
}

/** A starting point for each touch, in the firm's voice. Edit them to sound like you. */
export const STARTER_TEMPLATES = [
  {
    name: 'Connection request',
    channel: 'LINKEDIN',
    touch: 1,
    subject: null,
    body:
      'Hi {first name}, I run Jane Michael AI Talent Search. We place forward deployed and AI platform engineers ' +
      'with AI and security vendors selling into banks and insurers. I would like to connect and hear how {company} ' +
      'staffs its customer deployments.',
  },
  {
    name: 'First message',
    channel: 'LINKEDIN',
    touch: 2,
    subject: null,
    body: [
      'Thanks for connecting, {first name}.',
      '',
      'Deals with banks and insurers often stall after signing, when the product has to work inside the ' +
        'customer’s environment. We recruit the forward deployed and AI platform engineers who close that gap: ' +
        'people who have shipped production AI under bank-grade security and compliance.',
      '',
      'Is {company} hiring for these roles this quarter? If so, do you have 15 minutes this week for a quick call?',
      '',
      '{my first name}',
    ].join('\n'),
  },
  {
    name: 'Follow-up',
    channel: 'LINKEDIN',
    touch: 3,
    subject: null,
    body:
      'Hi {first name}, following up on my note. If {company} is adding deployment or MLOps engineers for ' +
      'financial services customers, I can send you a short list of people who have done it before. ' +
      'Would a 15-minute call this week work?',
  },
  {
    name: 'Last message',
    channel: 'LINKEDIN',
    touch: 4,
    subject: null,
    body:
      'Hi {first name}, I haven’t heard back, so I’ll assume the timing isn’t right. When {company} starts ' +
      'hiring forward deployed or AI platform engineers, reply here and I’ll send you a shortlist within a week.',
  },
];
