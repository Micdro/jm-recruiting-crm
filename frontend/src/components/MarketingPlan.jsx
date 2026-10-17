import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { formatDay } from '../format.js';
import { useAuth, useLoader, useToast, useUsers } from '../hooks.js';
import { POST_THEME, options } from '../labels.js';
import { WEEKDAYS, WEEKDAY_NAMES, monthName, monthOf, useLinkedIn } from '../marketing.js';
import { ConfirmButton, ErrorNote, ExternalLink, Loading } from './Bits.jsx';
import { SelectField, TextArea, TextField } from './Fields.jsx';

const LINKEDIN_APPS = 'https://www.linkedin.com/developers/apps';
const PAGE = 'page';

/** "user:12" or "page": who posts on a posting day, as one choice. */
function whoOf(slot) {
  return slot.account === 'COMPANY_PAGE' ? PAGE : slot.ownerId ? `user:${slot.ownerId}` : '';
}

/** The style AI writes in, the posting days, each month's focus, and posting to LinkedIn. */
export default function MarketingPlan({ today }) {
  const [month, setMonth] = useState(() => monthOf(today));
  const plan = useLoader(() => api.marketing.plan(month), [month], `marketing:plan:${month}`);

  return (
    <>
      {!plan.data && !plan.error && <Loading label="Loading the plan" />}
      <ErrorNote>{plan.error && errorMessage(plan.error)}</ErrorNote>
      {plan.data && (
        // Opened again when the month changes, so the form starts from that month's focus.
        <PlanForm key={month} data={plan.data} today={today} month={month} onMonth={setMonth} onSaved={plan.setData} />
      )}
      <LinkedInPanel />
    </>
  );
}

function PlanForm({ data, today, month, onMonth, onSaved }) {
  const toast = useToast();
  const { user } = useAuth();
  const users = useUsers();
  const [style, setStyle] = useState(data.style ?? '');
  const [pictureStyle, setPictureStyle] = useState(data.pictureStyle ?? '');
  const [focus, setFocus] = useState(data.focus ?? '');
  const [rhythm, setRhythm] = useState(() =>
    data.rhythm.map((slot, index) => ({ key: index, day: slot.day, who: whoOf(slot), theme: slot.theme })),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const people = users
    .filter((person) => person.active)
    .map((person) => ({ value: `user:${person.id}`, label: person.displayName }));
  const whoOptions = [...people, { value: PAGE, label: 'Company page' }];
  const setSlot = (key, field) => (value) =>
    setRhythm((current) => current.map((slot) => (slot.key === key ? { ...slot, [field]: value } : slot)));

  function addDay() {
    setRhythm((current) => [
      ...current,
      { key: Date.now(), day: 'TUESDAY', who: user ? `user:${user.id}` : PAGE, theme: 'OTHER' },
    ]);
  }

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const saved = await api.marketing.savePlan({
        style,
        pictureStyle,
        month,
        focus,
        rhythm: rhythm.map((slot) => ({
          day: slot.day,
          theme: slot.theme,
          account: slot.who === PAGE ? 'COMPANY_PAGE' : 'PERSONAL',
          ownerId: slot.who.startsWith('user:') ? Number(slot.who.slice(5)) : null,
        })),
      });
      onSaved(saved);
      toast('Plan saved');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const months = [monthOf(today), monthOf(today, 1), monthOf(today, 2)];

  return (
    <form className="plan-form" onSubmit={save}>
      <section className="panel" aria-labelledby="plan-style">
        <div className="panel-head">
          <h2 id="plan-style">Style</h2>
          <span className="panel-meta">Every AI draft and picture follows these.</span>
        </div>
        <div className="form">
          <TextArea
            label="How the posts should sound"
            rows={6}
            value={style}
            onChange={setStyle}
            maxLength={4000}
            placeholder={'For example:\nShort sentences, first person, no hype.\nOpen with something we saw this week.\nEnd with a question.\nUse "FDE", not "forward deployed engineer", after the first mention.'}
            hint="Tone, words to use or avoid, how to open and close. Paste a post you liked as an example."
          />
          <TextArea
            label="How AI pictures should look"
            rows={2}
            value={pictureStyle}
            onChange={setPictureStyle}
            maxLength={1000}
            placeholder="For example: flat navy and gold shapes, lots of space, no people"
            hint="Pictures never have words, logos or real people in them."
          />
        </div>
      </section>

      <section className="panel" aria-labelledby="plan-days">
        <div className="panel-head">
          <h2 id="plan-days">Posting days</h2>
          <span className="panel-meta">Set up the week fills these with drafts.</span>
        </div>
        {rhythm.length === 0 && <p className="muted">No posting days yet. Add the days you want to post.</p>}
        <ul className="plan-days">
          {rhythm.map((slot) => (
            <li key={slot.key} className="plan-day">
              <SelectField
                label="Day"
                value={slot.day}
                onChange={setSlot(slot.key, 'day')}
                options={WEEKDAYS.map((day) => ({ value: day, label: WEEKDAY_NAMES[day] }))}
                required
              />
              <SelectField label="Who posts" value={slot.who} onChange={setSlot(slot.key, 'who')} options={whoOptions} required />
              <SelectField label="About" value={slot.theme} onChange={setSlot(slot.key, 'theme')} options={options(POST_THEME)} required />
              <button
                type="button"
                className="btn btn-sm btn-quiet plan-day-remove"
                onClick={() => setRhythm((current) => current.filter((item) => item.key !== slot.key))}
                aria-label={`Remove ${WEEKDAY_NAMES[slot.day]}`}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
        <button type="button" className="btn btn-sm" onClick={addDay}>
          Add a posting day
        </button>
      </section>

      <section className="panel" aria-labelledby="plan-focus">
        <div className="panel-head">
          <h2 id="plan-focus">Focus for the month</h2>
        </div>
        <div className="form">
          <SelectField
            label="Month"
            value={month}
            onChange={onMonth}
            options={months.map((value) => ({ value, label: monthName(value) }))}
            required
          />
          <TextArea
            label={`What ${monthName(month)}’s posts should keep coming back to`}
            rows={3}
            value={focus}
            onChange={setFocus}
            maxLength={2000}
            placeholder="For example: insurers starting to hire FDEs, and what model risk teams ask of AI vendors"
          />
        </div>
      </section>

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions form-actions-start">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save plan'}
        </button>
      </div>
    </form>
  );
}

/** The firm's LinkedIn app (the owner's to add) and each person's own connection. */
function LinkedInPanel() {
  const toast = useToast();
  const linkedin = useLinkedIn();
  const [editing, setEditing] = useState(false);
  const [app, setApp] = useState({ clientId: '', clientSecret: '' });
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');
  const data = linkedin.data;

  async function act(name, call, done) {
    setBusy(name);
    setError('');
    try {
      const result = await call();
      if (result) linkedin.setData(result);
      else linkedin.reload();
      if (done) toast(done);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(null);
    }
  }

  async function connect() {
    setBusy('connect');
    setError('');
    try {
      const { url } = await api.linkedin.connect();
      window.location.assign(url);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(null);
    }
  }

  async function saveApp(event) {
    event.preventDefault();
    if (await act('app', () => api.linkedin.saveApp(app), 'LinkedIn app saved')) {
      setApp({ clientId: '', clientSecret: '' });
      setEditing(false);
    }
  }

  if (!data) return linkedin.error ? <ErrorNote>{errorMessage(linkedin.error)}</ErrorNote> : null;

  const me = data.me;
  return (
    <section className="panel" aria-labelledby="plan-linkedin">
      <div className="panel-head">
        <h2 id="plan-linkedin">Posting to LinkedIn</h2>
        <span className="panel-meta">Post to LinkedIn publishes straight to your own profile. Company page posts stay copy and paste.</span>
      </div>
      <ErrorNote>{error}</ErrorNote>

      {!data.appAdded && !data.canManage && (
        <p className="muted">Waiting for {data.owner ?? 'the owner'} to add the firm’s LinkedIn app.</p>
      )}

      {data.appAdded && (
        <div className="setting-row">
          {me ? (
            <p>
              <strong>Connected as {me.name ?? 'you'}</strong>
              {me.expired
                ? 'The connection has run out. Connect again to keep posting.'
                : `Works until ${formatDay(me.expiresAt)}. LinkedIn asks you to connect again every 60 days.`}
            </p>
          ) : (
            <p>
              <strong>Your LinkedIn isn’t connected</strong>
              Connect it once on this computer to post from the CRM.
            </p>
          )}
          <span className="setting-actions">
            {(!me || me.expired || me.expiring) && (
              <button type="button" className="btn btn-sm btn-primary" disabled={busy === 'connect'} onClick={connect}>
                {busy === 'connect' ? 'Opening LinkedIn…' : me ? 'Connect again' : 'Connect my LinkedIn'}
              </button>
            )}
            {me && (
              <ConfirmButton
                label="Disconnect"
                confirmLabel="Disconnect"
                className="btn btn-sm btn-quiet"
                onConfirm={() => act('disconnect', () => api.linkedin.disconnect(), 'LinkedIn disconnected')}
              />
            )}
          </span>
        </div>
      )}

      {data.canManage && data.appAdded && !editing && (
        <div className="setting-row">
          <p>
            <strong>The firm’s LinkedIn app</strong>
            Client ID ending in {data.clientIdHint ?? '••••'}. Stored encrypted and shared with the team’s computers.
          </p>
          <span className="setting-actions">
            <button type="button" className="btn btn-sm" onClick={() => setEditing(true)}>
              Replace
            </button>
            <ConfirmButton
              label="Remove"
              confirmLabel="Remove the app"
              className="btn btn-sm btn-quiet"
              onConfirm={() => act('app', () => api.linkedin.removeApp(), 'LinkedIn app removed')}
            />
          </span>
        </div>
      )}

      {data.canManage && (!data.appAdded || editing) && (
        <form className="form finder-form" onSubmit={saveApp}>
          <ol className="linkedin-steps">
            <li>
              Create an app for the firm’s LinkedIn page on <ExternalLink href={LINKEDIN_APPS}>LinkedIn Developers</ExternalLink>.
            </li>
            <li>
              On its <strong>Products</strong> tab, add <strong>Share on LinkedIn</strong> and{' '}
              <strong>Sign In with LinkedIn using OpenID Connect</strong>.
            </li>
            <li>
              On its <strong>Auth</strong> tab, add this as an authorized redirect URL: <code>{data.redirectUrl}</code>
            </li>
            <li>Paste the app’s Client ID and Primary Client Secret here.</li>
          </ol>
          <div className="form-row">
            <TextField
              label="Client ID"
              autoComplete="off"
              spellCheck={false}
              value={app.clientId}
              onChange={(value) => setApp((current) => ({ ...current, clientId: value }))}
              required
            />
            <TextField
              label="Primary Client Secret"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={app.clientSecret}
              onChange={(value) => setApp((current) => ({ ...current, clientSecret: value }))}
              required
            />
          </div>
          <div className="form-actions form-actions-start">
            <button type="submit" className="btn btn-primary" disabled={busy === 'app'}>
              {busy === 'app' ? 'Saving…' : 'Save app'}
            </button>
            {editing && (
              <button type="button" className="btn btn-quiet" onClick={() => setEditing(false)}>
                Cancel
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
