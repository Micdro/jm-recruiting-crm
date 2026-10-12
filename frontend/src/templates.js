import { MAX_TOUCHES } from './labels.js';

/** The channel for each touch of the sequence: LinkedIn, email, LinkedIn, email. */
const SEQUENCE = ['LINKEDIN', 'EMAIL', 'LINKEDIN', 'EMAIL'];

/** LinkedIn cuts connection request notes off at this many characters. */
export const LINKEDIN_NOTE_LIMIT = 300;

/** The words people can put in braces, and where each comes from. */
export const PLACEHOLDERS = [
  { key: 'first name', label: 'Their first name', value: (contact) => firstName(contact?.name) },
  { key: 'name', label: 'Their full name', value: (contact) => contact?.name },
  { key: 'title', label: 'Their title', value: (contact) => contact?.title },
  { key: 'company', label: 'Their company', value: (contact) => contact?.companyName },
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
    name: 'Intro email',
    channel: 'EMAIL',
    touch: 2,
    subject: 'Forward deployed engineers for {company}',
    body: [
      'Hi {first name},',
      '',
      'Deals with banks and insurers often stall after signing, when the product has to work inside the ' +
        'customer’s environment. We recruit the forward deployed and AI platform engineers who close that gap: ' +
        'people who have shipped production AI under bank-grade security and compliance.',
      '',
      'Are you hiring for these roles this quarter? If so, do you have 15 minutes this week for a quick call?',
      '',
      '{my first name}',
      'Jane Michael AI Talent Search',
      'search.jane-michael.com',
    ].join('\n'),
  },
  {
    name: 'Follow-up message',
    channel: 'LINKEDIN',
    touch: 3,
    subject: null,
    body:
      'Hi {first name}, following up on my note. If {company} is adding deployment or MLOps engineers for ' +
      'financial services customers, I can send you a short list of people who have done it before. ' +
      'Would a 15-minute call this week work?',
  },
  {
    name: 'Last email',
    channel: 'EMAIL',
    touch: 4,
    subject: 'Closing the loop',
    body: [
      'Hi {first name},',
      '',
      'I haven’t heard back, so I’ll assume the timing isn’t right. When {company} starts hiring forward ' +
        'deployed or AI platform engineers, reply to this email and I’ll send you a shortlist within a week.',
      '',
      '{my first name}',
      'Jane Michael AI Talent Search',
    ].join('\n'),
  },
];
