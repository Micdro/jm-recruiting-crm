import { useCallback, useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, ExternalLink, Loading, PageHeader } from '../components/Bits.jsx';
import AiSettingsPanel from '../components/AiSettingsPanel.jsx';
import Modal from '../components/Modal.jsx';
import { CheckboxField, TextArea, TextField } from '../components/Fields.jsx';
import { formatTimestamp, href, plural } from '../format.js';
import { useInterval, useLoader, useTitle, useToast } from '../hooks.js';
import { DISMISS_REASON, HIRING_NEED, INDUSTRY, SEGMENT } from '../labels.js';

const KEY_HELP = 'https://platform.claude.com/docs/en/get-api-key';

/** "$0.52" from cents. */
function dollars(cents) {
  return `$${(cents / 100).toFixed(2)}`;
}

/**
 * The finder writes each company's notes as paragraphs: when it was found, "Why now:", "What they
 * do:" and "Sources:" with one link per line. Notes someone has edited still show as they are.
 */
function readFoundNotes(notes) {
  const parts = String(notes ?? '').split(/\n\s*\n/);
  const why = parts.find((part) => part.startsWith('Why now: '));
  const sources = parts.find((part) => part.startsWith('Sources:'));
  return {
    why: why ? why.slice('Why now: '.length).trim() : null,
    sources: sources
      ? sources
          .split('\n')
          .slice(1)
          .map((line) => line.trim())
          .filter((line) => /^https?:\/\//i.test(line))
      : [],
  };
}

function sourceLabel(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** New companies the prospect finder found on the web, waiting to be accepted or dismissed. */
export default function ProspectsPage() {
  useTitle('AI Found');
  const toast = useToast();
  const status = useLoader(() => api.prospects.status(), [], 'prospects:status');
  const found = useLoader(() => api.prospects.companies(), [], 'prospects:companies');
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');

  const data = status.data;
  const running = Boolean(data?.running);
  const reloadStatus = status.reload;
  const reloadFound = found.reload;

  // While a search runs, check every 15 seconds so new companies show up when it's done.
  const poll = useCallback(() => {
    if (running) {
      reloadStatus();
      reloadFound();
    }
  }, [running, reloadStatus, reloadFound]);
  useInterval(poll, 15_000);

  async function runNow() {
    setBusy('run');
    setError('');
    try {
      status.setData(await api.prospects.run());
      toast('Searching. New companies show up here in a few minutes.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  const [dismissing, setDismissing] = useState(null);

  async function review(company, action, why) {
    setBusy(company.id);
    setError('');
    setDismissing(null);
    try {
      const result = action === 'accept' ? await api.prospects.accept(company.id) : await api.prospects.dismiss(company.id, why);
      found.setData((list) => (list ?? []).filter((item) => item.id !== company.id));
      reloadStatus();
      if (action === 'accept') {
        toast(`${result.name} is now a lead. Its people join the outreach queue.`);
      } else {
        const removed = result.contactsRemoved > 0 ? ` ${plural(result.contactsRemoved, 'person', 'people')} moved to the Trash.` : '';
        toast(`${result.name} dismissed. It won't be suggested again.${removed}`);
      }
    } catch (err) {
      setError(errorMessage(err));
      reloadFound();
    } finally {
      setBusy(null);
    }
  }

  const list = found.data;

  return (
    <>
      <PageHeader
        title="AI Found"
        sub="Companies the prospect finder found on the web: AI and security vendors selling into financial services with a reason to hire now."
      >
        {data?.configured && (
          <button type="button" className="btn btn-primary" disabled={running || busy === 'run'} onClick={runNow}>
            {running ? 'Searching…' : 'Find now'}
          </button>
        )}
      </PageHeader>

      <ErrorNote>{error || (status.error && errorMessage(status.error)) || (found.error && errorMessage(found.error))}</ErrorNote>
      {!data && !status.error && <Loading label="Loading the prospect finder" />}

      {data && <FinderStatus data={data} />}

      {data?.configured && list && list.length === 0 && !running && (
        <section className="panel">
          <Empty title="Nothing waiting for review">
            New companies show up here after each search. Accepted ones become leads on Companies; dismissed ones are never
            suggested again.
          </Empty>
        </section>
      )}

      {list && list.length > 0 && (
        <div className="prospect-list">
          {list.map((company) => (
            <FoundCompany
              key={company.id}
              company={company}
              busy={busy === company.id}
              onAccept={() => review(company, 'accept')}
              onDismiss={() => setDismissing(company)}
            />
          ))}
        </div>
      )}

      {data && <FinderSettings data={data} onSaved={status.setData} />}

      {dismissing && (
        <Modal title={`Why isn’t ${dismissing.name} a fit?`} onClose={() => setDismissing(null)}>
          <DismissForm
            company={dismissing}
            onCancel={() => setDismissing(null)}
            onDismiss={(why) => review(dismissing, 'dismiss', why)}
          />
        </Modal>
      )}
    </>
  );
}

function FinderStatus({ data }) {
  const last = data.lastRun;

  if (!data.configured) {
    return (
      <section className="panel">
        <Empty title="Add a Claude API key to start">
          {data.canEditLimits
            ? 'The finder uses Claude with web search, billed to your Anthropic account. Add the key under Spending below.'
            : `The finder uses Claude with web search, billed to your Anthropic account. Ask ${data.ownerName ?? 'the owner'} to add the API key.`}
        </Empty>
      </section>
    );
  }

  return (
    <section className="panel finder-status" aria-label="Prospect finder">
      {data.running ? (
        <p className="finder-line">
          <strong>Searching the web now.</strong> New companies appear here when it’s done, usually within a few minutes.
        </p>
      ) : last ? (
        <LastRunLine last={last} />
      ) : (
        <p className="finder-line">No searches yet.</p>
      )}
      {last?.status === 'FAILED' && last.message && <p className="warn-note">{last.message}</p>}
      <p className="finder-line muted">
        {data.enabled && data.runsOn
          ? `Searches each weekday on ${data.runsHere ? 'this computer' : data.runsOn}, the first time the CRM is unlocked after 7 AM. `
          : 'Weekday searches are off. '}
        {`Spent ${dollars(data.spentTodayCents)} of today’s $${data.dailyLimitDollars} limit and ${dollars(data.spentThisMonthCents)} of this month’s $${data.monthlyLimitDollars}.`}
      </p>
    </section>
  );
}

function LastRunLine({ last }) {
  const when = formatTimestamp(last.finishedAt ?? last.startedAt);
  const where = [last.startedBy ? `started by ${last.startedBy}` : null, last.computer ? `on ${last.computer}` : null]
    .filter(Boolean)
    .join(' ');
  if (last.status === 'FAILED') {
    return (
      <p className="finder-line">
        <strong>Last search didn’t finish</strong> ({when}
        {where ? `, ${where}` : ''}).
      </p>
    );
  }
  const parts = [`added ${plural(last.added, 'company', 'companies')}`];
  if (last.skipped > 0) parts.push(`${last.skipped} already in the CRM`);
  parts.push(plural(last.contacts, 'person', 'people'));
  return (
    <p className="finder-line">
      <strong>Last search {when}</strong>
      {where ? ` ${where}` : ''}: {parts.join(', ')}. Cost {dollars(last.costCents)} ({plural(last.searches, 'web search', 'web searches')}).
      {last.withheld > 0 && (
        <span className="muted">
          {' '}
          {plural(last.withheld, 'contact detail', 'contact details')} left out because the searches didn’t show them.
        </span>
      )}
      {last.message && <span className="muted"> {last.message}</span>}
    </p>
  );
}

/** The reason teaches the finder: its next searches avoid companies dismissed for the same reason. */
function DismissForm({ company, onCancel, onDismiss }) {
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');

  return (
    <form
      className="form"
      onSubmit={(event) => {
        event.preventDefault();
        onDismiss({ reason: reason || 'OTHER', note: note.trim() || undefined });
      }}
    >
      <p className="form-intro">
        {company.name} moves to Not qualified and won’t be suggested again. The finder reads your reason and looks for
        fewer companies like it.
      </p>
      <fieldset className="radio-group">
        <legend>Reason</legend>
        {Object.entries(DISMISS_REASON).map(([value, label]) => (
          <label key={value} className="radio">
            <input type="radio" name="dismiss-reason" checked={reason === value} onChange={() => setReason(value)} />
            {label}
          </label>
        ))}
      </fieldset>
      <TextArea
        label="Note (optional)"
        rows={2}
        value={note}
        onChange={setNote}
        maxLength={300}
        placeholder="For example: sells to hospitals, not banks."
      />
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={!reason}>
          Dismiss
        </button>
      </div>
    </form>
  );
}

function FoundCompany({ company, busy, onAccept, onDismiss }) {
  const { why, sources } = readFoundNotes(company.notes);
  const tags = [
    company.industry && company.industry !== 'UNCLEAR' ? INDUSTRY[company.industry] : null,
    ...(company.segments ?? []).map((segment) => SEGMENT[segment]),
    company.hiringNeed && company.hiringNeed !== 'NOT_SURE' ? `Needs: ${HIRING_NEED[company.hiringNeed]}` : null,
  ].filter(Boolean);

  return (
    <article className="panel prospect" aria-labelledby={`prospect-${company.id}`}>
      <div className="prospect-head">
        <div>
          <h2 id={`prospect-${company.id}`}>
            <Link to={`/companies/${company.id}`}>{company.name}</Link>
          </h2>
          <p className="prospect-meta">
            {[company.location, company.foundAt ? `found ${formatTimestamp(company.foundAt)}` : null].filter(Boolean).join(' · ')}
            {company.website && (
              <>
                {' · '}
                <ExternalLink href={href(company.website)}>{sourceLabel(href(company.website))}</ExternalLink>
              </>
            )}
          </p>
        </div>
        <div className="prospect-actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={onAccept}>
            Accept
          </button>
          <button type="button" className="btn btn-sm" disabled={busy} onClick={onDismiss}>
            Dismiss
          </button>
        </div>
      </div>

      {company.focus && <p className="prospect-focus">{company.focus}</p>}
      {why ? (
        <p className="prospect-why">
          <strong>Why now:</strong> {why}
        </p>
      ) : (
        company.notes && <p className="notes">{company.notes}</p>
      )}

      {(tags.length > 0 || sources.length > 0) && (
        <p className="prospect-tags">
          {tags.map((tag) => (
            <span key={tag} className="tag">
              {tag}
            </span>
          ))}
          {sources.map((url) => (
            <span key={url} className="prospect-source">
              <ExternalLink href={url}>{sourceLabel(url)}</ExternalLink>
            </span>
          ))}
        </p>
      )}

      <h3 className="panel-subhead">Who would hire</h3>
      {company.contacts.length === 0 ? (
        <p className="muted">No one found yet. Add people from LinkedIn after you accept it.</p>
      ) : (
        <ul className="prospect-people">
          {company.contacts.map((person) => (
            <li key={person.id}>
              <Link to={`/contacts/${person.id}`} className="prospect-person">
                {person.name}
              </Link>
              <span className="muted">{person.title}</span>
              <span className="prospect-reach">
                {person.linkedinUrl && <ExternalLink href={person.linkedinUrl}>LinkedIn</ExternalLink>}
                {person.email && <a href={`mailto:${person.email}`}>{person.email}</a>}
                {person.phone && <a href={`tel:${person.phone.replace(/[^\d+]/g, '')}`}>{person.phone}</a>}
                {!person.linkedinUrl && !person.email && !person.phone && <span className="muted">No public profile found</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function FinderSettings({ data, onSaved }) {
  return (
    <>
      <SpendingSettings data={data} onSaved={onSaved} />
      {/* Opened again when the Claude key comes or goes, so it shows whether drafting can work. */}
      <AiSettingsPanel key={data.configured ? 'claude-key' : 'no-claude-key'} />
      <SearchSettings data={data} onSaved={onSaved} />
    </>
  );
}

/** Calls the API and hands back the finder's new state, with a toast or an error. */
function useSettingsAction(onSaved) {
  const toast = useToast();
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');

  async function act(name, call, done) {
    setBusy(name);
    setError('');
    try {
      onSaved(await call());
      if (done) toast(done);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(null);
    }
  }

  return { busy, error, act };
}

/** The API key and what the finder may spend: the account owner's to change. */
function SpendingSettings({ data, onSaved }) {
  const { busy, error, act } = useSettingsAction(onSaved);
  const [editingKey, setEditingKey] = useState(false);
  const [key, setKey] = useState('');
  const [limits, setLimits] = useState(() => ({
    dailyLimitDollars: String(data.dailyLimitDollars),
    monthlyLimitDollars: String(data.monthlyLimitDollars),
  }));
  const setLimit = (field) => (value) => setLimits((current) => ({ ...current, [field]: value }));

  if (!data.canEditLimits) {
    return (
      <section className="panel" aria-labelledby="finder-spending">
        <div className="panel-head">
          <h2 id="finder-spending">Spending</h2>
        </div>
        <p className="panel-meta">
          {data.configured ? `Claude API key ending in ${data.keyHint ?? '••••'}. ` : 'No Claude API key yet. '}
          AI use, searches and drafts together, stops at ${data.dailyLimitDollars} a day and ${data.monthlyLimitDollars} a month. Only{' '}
          {data.ownerName ?? 'the owner'} can change the key and these limits.
        </p>
      </section>
    );
  }

  async function saveKey(event) {
    event.preventDefault();
    if (await act('key', () => api.prospects.saveKey(key), 'API key saved')) {
      setKey('');
      setEditingKey(false);
    }
  }

  function saveLimits(event) {
    event.preventDefault();
    act(
      'limits',
      () =>
        api.prospects.saveLimits({
          dailyLimitDollars: Number(limits.dailyLimitDollars),
          monthlyLimitDollars: Number(limits.monthlyLimitDollars),
        }),
      'Limits saved',
    );
  }

  return (
    <section className="panel" aria-labelledby="finder-spending">
      <div className="panel-head">
        <h2 id="finder-spending">Spending</h2>
        <span className="panel-meta">{data.ownerName ? 'Only you can change these.' : 'No owner is set, so any admin can change these.'}</span>
      </div>
      <ErrorNote>{error}</ErrorNote>

      {data.configured && !editingKey ? (
        <div className="setting-row">
          <p>
            <strong>Claude API key</strong>
            Ends in {data.keyHint ?? '••••'}. Stored encrypted and shared with the team’s computers.
          </p>
          <span className="setting-actions">
            <button type="button" className="btn btn-sm" onClick={() => setEditingKey(true)}>
              Replace
            </button>
            <ConfirmButton
              label="Remove"
              confirmLabel="Remove the key"
              className="btn btn-sm btn-quiet"
              onConfirm={() => act('key', () => api.prospects.removeKey(), 'API key removed')}
            />
          </span>
        </div>
      ) : (
        <form className="form" onSubmit={saveKey}>
          <p className="form-intro">
            Create a key in the Claude Console (<ExternalLink href={KEY_HELP}>how to get one</ExternalLink>) and paste it here. Searches are
            billed to that account, and this page shows what each one cost. The CRM checks the key before saving it.
          </p>
          <div className="inline-form">
            <TextField
              label="Claude API key"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={key}
              onChange={setKey}
              placeholder="sk-ant-…"
              required
            />
            <button type="submit" className="btn btn-primary" disabled={busy === 'key' || !key.trim()}>
              {busy === 'key' ? 'Checking…' : 'Save key'}
            </button>
            {data.configured && (
              <button type="button" className="btn btn-quiet" onClick={() => setEditingKey(false)}>
                Cancel
              </button>
            )}
          </div>
        </form>
      )}

      <form className="form finder-form" onSubmit={saveLimits}>
        <div className="form-row">
          <TextField
            label="Daily limit (US dollars)"
            type="number"
            min="1"
            max="100"
            value={limits.dailyLimitDollars}
            onChange={setLimit('dailyLimitDollars')}
            hint="Covers all AI use: searches, message drafts and post drafts. A search stops before it would pass this and saves what it found so far."
            required
          />
          <TextField
            label="Monthly limit (US dollars)"
            type="number"
            min="0"
            max="1000"
            value={limits.monthlyLimitDollars}
            onChange={setLimit('monthlyLimitDollars')}
            hint="AI use stops for the month once this is spent."
            required
          />
        </div>
        <div className="form-actions form-actions-start">
          <button type="submit" className="btn btn-primary" disabled={busy === 'limits'}>
            {busy === 'limits' ? 'Saving…' : 'Save limits'}
          </button>
        </div>
      </form>
    </section>
  );
}

/** When the finder searches and what it looks for: any admin's to change. */
function SearchSettings({ data, onSaved }) {
  const { busy, error, act } = useSettingsAction(onSaved);
  const [form, setForm] = useState(() => ({
    enabled: data.enabled,
    perRun: String(data.perRun),
    focus: data.focus ?? '',
  }));
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));

  if (!data.canEdit) {
    return (
      <section className="panel" aria-labelledby="finder-settings">
        <div className="panel-head">
          <h2 id="finder-settings">Search settings</h2>
        </div>
        <p className="panel-meta">
          Up to {plural(data.perRun, 'company', 'companies')} a search{data.focus ? `, with this guidance: ${data.focus}` : ''}. Only admins
          can change the search settings.
        </p>
      </section>
    );
  }

  function saveSchedule(event) {
    event.preventDefault();
    act(
      'settings',
      () => api.prospects.saveSettings({ enabled: form.enabled, perRun: Number(form.perRun), focus: form.focus }),
      'Settings saved',
    );
  }

  return (
    <section className="panel" aria-labelledby="finder-settings">
      <div className="panel-head">
        <h2 id="finder-settings">Search settings</h2>
      </div>
      <ErrorNote>{error}</ErrorNote>

      <form className="form" onSubmit={saveSchedule}>
        <CheckboxField
          label="Search each weekday"
          checked={form.enabled}
          onChange={set('enabled')}
          hint="The first time the CRM is unlocked after 7 AM on the computer below. Find now works any time."
        />
        <div className="form-row">
          <TextField label="Companies per search" type="number" min="1" max="25" value={form.perRun} onChange={set('perRun')} required />
        </div>
        <TextArea
          label="Extra guidance"
          rows={3}
          value={form.focus}
          onChange={set('focus')}
          maxLength={2000}
          placeholder="For example: US and Canada only. Skip crypto companies. Favor insurers."
          hint="Added to what the finder already looks for: AI and security vendors selling into banks, insurers and financial services, hiring FDEs or AI platform engineers."
        />
        <div className="form-actions form-actions-start">
          <button type="submit" className="btn btn-primary" disabled={busy === 'settings'}>
            {busy === 'settings' ? 'Saving…' : 'Save settings'}
          </button>
        </div>
      </form>

      {data.runsOn && (
        <div className="setting-row finder-form">
          <p>
            <strong>Runs on {data.runsHere ? 'this computer' : data.runsOn}</strong>
            One computer runs the weekday search, so the team pays for one search a day, not three.
          </p>
          {!data.runsHere && (
            <button type="button" className="btn btn-sm" disabled={busy === 'here'} onClick={() => act('here', () => api.prospects.runHere(), 'This computer runs the search now')}>
              Run it here instead
            </button>
          )}
        </div>
      )}
    </section>
  );
}
