import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { href } from '../format.js';
import { useAuth, useLoader } from '../hooks.js';
import { MAX_TOUCHES } from '../labels.js';
import { LINKEDIN_NOTE_LIMIT, bestTemplate, channelForTouch, fill, leftovers, nextTouch } from '../templates.js';
import { ErrorNote } from './Bits.jsx';

const CHANNELS = [
  ['LINKEDIN', 'LinkedIn'],
  ['EMAIL', 'Email'],
];

/**
 * Writes the next touch for a contact from a saved template: fills in their details, copies the
 * message or opens it in the email app or on LinkedIn, then logs the touch.
 */
export default function MessageComposer({ contact, onLogged }) {
  const { user } = useAuth();
  const templates = useLoader(() => api.templates.list(), [], 'templates');
  const cold = contact.status === 'NEW' || contact.status === 'IN_SEQUENCE';
  const touch = nextTouch(contact);
  const suggested =
    contact.nextActionType === 'EMAIL' || contact.nextActionType === 'LINKEDIN'
      ? contact.nextActionType
      : channelForTouch(touch);

  const [channel, setChannel] = useState(suggested);
  const [chosenId, setChosenId] = useState(null);
  const [edited, setEdited] = useState(null);
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  const all = templates.data ?? [];
  const onChannel = all.filter((template) => template.channel === channel);
  const chosen =
    onChannel.find((template) => template.id === chosenId) ?? bestTemplate(all, channel, cold ? touch : null, user?.id);

  // What the template gives, unless the person has typed over it.
  const subject = edited?.subject ?? fill(chosen?.subject, contact, user);
  const body = edited?.body ?? fill(chosen?.body, contact, user);
  const missing = leftovers(`${channel === 'EMAIL' ? subject : ''} ${body}`);

  const blocked =
    contact.status === 'DO_NOT_CONTACT'
      ? `${contact.name} is marked Do not contact.`
      : (cold && contact.touchCount >= MAX_TOUCHES) || contact.status === 'NO_RESPONSE' || contact.status === 'NOT_INTERESTED'
        ? 'The sequence is over. Restart it on the contact’s page to reach out again.'
        : '';

  function choose(nextChannel, templateId = null) {
    setChannel(nextChannel);
    setChosenId(templateId);
    setEdited(null);
    setCopied(false);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(channel === 'EMAIL' && subject ? `${subject}\n\n${body}` : body);
      setCopied(true);
      setSent(true);
      return true;
    } catch {
      setError('Couldn’t copy. Select the message and copy it yourself.');
      return false;
    }
  }

  async function openLinkedIn() {
    if (await copy()) {
      window.open(href(contact.linkedinUrl), '_blank', 'noopener');
    }
  }

  function openEmail() {
    const query = new URLSearchParams({ subject, body }).toString().replace(/\+/g, '%20');
    window.location.href = `mailto:${encodeURIComponent(contact.email)}?${query}`;
    setSent(true);
  }

  async function logTouch() {
    setBusy(true);
    setError('');
    try {
      const updated = await api.contacts.logActivity(contact.id, { type: channel });
      onLogged(updated, channel);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const tooLongForNote = channel === 'LINKEDIN' && touch === 1 && cold && body.length > LINKEDIN_NOTE_LIMIT;

  return (
    <div className="form composer">
      <p className="composer-touch">
        {cold ? `Touch ${touch} of ${MAX_TOUCHES}. ` : ''}
        {contact.title ? `${contact.title}, ` : ''}
        {contact.companyName}
      </p>

      <fieldset className="segmented segmented-small">
        <legend className="visually-hidden">Send by</legend>
        {CHANNELS.map(([value, label]) => (
          <label key={value} className={channel === value ? 'segment segment-on' : 'segment'}>
            <input type="radio" name="composer-channel" checked={channel === value} onChange={() => choose(value)} />
            {label}
            {value === suggested && cold ? ' (this touch)' : ''}
          </label>
        ))}
      </fieldset>

      {templates.data && onChannel.length === 0 && (
        <p className="setup-note">
          No {channel === 'EMAIL' ? 'email' : 'LinkedIn'} templates yet. Write the message below, or{' '}
          <Link to="/templates">add templates</Link>.
        </p>
      )}

      {onChannel.length > 1 && (
        <div className="field">
          <label htmlFor="composer-template">Template</label>
          <select
            id="composer-template"
            value={chosen?.id ?? ''}
            onChange={(event) => choose(channel, Number(event.target.value))}
          >
            {onChannel.map((template) => (
              <option key={template.id} value={template.id}>
                {template.name}
                {template.touch ? ` (touch ${template.touch})` : ''}
              </option>
            ))}
          </select>
        </div>
      )}

      {channel === 'EMAIL' && (
        <div className="field">
          <label htmlFor="composer-subject">Subject</label>
          <input
            id="composer-subject"
            value={subject}
            onChange={(event) => setEdited({ subject: event.target.value, body })}
          />
        </div>
      )}
      <div className="field">
        <label htmlFor="composer-body">Message</label>
        <textarea
          id="composer-body"
          rows={channel === 'EMAIL' ? 12 : 7}
          value={body}
          onChange={(event) => setEdited({ subject, body: event.target.value })}
        />
        <p className={`field-hint ${tooLongForNote ? 'composer-warn' : ''}`}>
          {body.length} characters
          {channel === 'LINKEDIN' && touch === 1 && cold
            ? `. LinkedIn connection notes stop at ${LINKEDIN_NOTE_LIMIT}.`
            : ''}
        </p>
      </div>

      {missing.length > 0 && <p className="warn-note">Fill in by hand: {missing.join(', ')}.</p>}
      {blocked && <p className="warn-note">{blocked}</p>}
      <ErrorNote>{error}</ErrorNote>

      <div className="form-actions form-actions-start">
        {channel === 'EMAIL' ? (
          <button
            type="button"
            className={`btn ${sent ? '' : 'btn-primary'}`}
            disabled={!contact.email || !body}
            onClick={openEmail}
            title={contact.email ? undefined : 'No email address on file'}
          >
            Open in email
          </button>
        ) : (
          contact.linkedinUrl && (
            <button type="button" className={`btn ${sent ? '' : 'btn-primary'}`} disabled={!body} onClick={openLinkedIn}>
              Copy and open LinkedIn
            </button>
          )
        )}
        <button type="button" className="btn" disabled={!body} onClick={copy}>
          {copied ? 'Copied' : 'Copy message'}
        </button>
        <span className="spacer" />
        <button
          type="button"
          className={`btn ${sent ? 'btn-primary' : ''}`}
          disabled={busy || Boolean(blocked)}
          onClick={logTouch}
        >
          {busy ? 'Logging…' : `Log ${channel === 'EMAIL' ? 'email' : 'LinkedIn message'} as sent`}
        </button>
      </div>
      {channel === 'EMAIL' && !contact.email && <p className="field-hint">No email address on file. Copy the message instead.</p>}
      {channel === 'LINKEDIN' && !contact.linkedinUrl && (
        <p className="field-hint">No LinkedIn profile on file. Copy the message and find them on LinkedIn.</p>
      )}
    </div>
  );
}
