import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, ExternalLink, Loading, PageHeader, Pill } from '../components/Bits.jsx';
import { CheckboxGroup, OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import Modal from '../components/Modal.jsx';
import { formatDate, formatWeekday, href, parseDate, plural, toIsoDate, todayIso } from '../format.js';
import { useAuth, useLoader, useTitle, useToast, useUsers } from '../hooks.js';
import { POST_ACCOUNT, POST_STATUS, POST_THEME, options } from '../labels.js';

/** LinkedIn's limit for a post. */
const POST_LIMIT = 3000;
const TITLE_LIMIT = 200;

/** The Monday of the week holding a YYYY-MM-DD day. */
function mondayOf(iso) {
  const date = parseDate(iso);
  date.setDate(date.getDate() - ((date.getDay() + 6) % 7));
  return toIsoDate(date);
}

function addDays(iso, days) {
  const date = parseDate(iso);
  date.setDate(date.getDate() + days);
  return toIsoDate(date);
}

/** "$0.02" from cents. */
function dollars(cents) {
  return `$${(cents / 100).toFixed(2)}`;
}

/** "Claude (Claude Sonnet 5.5)", or what's missing before drafting can work. */
function aiLine(ai) {
  if (!ai) return null;
  const current = ai.providers.find((provider) => provider.id === ai.provider);
  if (!current) return null;
  const model = current.models.find((option) => option.id === current.model)?.label ?? current.model;
  return current.hasKey
    ? `Drafts are written by ${model}.`
    : `Drafting needs a ${current.label} API key first.`;
}

/** The week's LinkedIn posts, the ideas waiting for a day, and AI drafts. Posting itself happens on LinkedIn. */
export default function MarketingPage() {
  useTitle('Marketing');
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const today = todayIso();
  const thisWeek = mondayOf(today);
  const start = mondayOf(params.get('week') || today);
  const week = useLoader(() => api.marketing.week(start), [start], `marketing:week:${start}`);
  const ai = useLoader(() => api.ai.status(), [], 'ai:status');
  const [editing, setEditing] = useState(null);
  const [suggesting, setSuggesting] = useState(false);

  function goTo(monday) {
    const next = new URLSearchParams(params);
    if (monday === thisWeek) next.delete('week');
    else next.set('week', monday);
    setParams(next, { replace: true });
  }

  const data = week.data;
  const days = Array.from({ length: 7 }, (_, index) => addDays(start, index));
  const planned = data?.planned ?? [];
  const ideas = data?.ideas ?? [];

  return (
    <>
      <PageHeader
        title="Marketing"
        sub="Plan the week’s LinkedIn posts, draft them with AI, and see which ones start conversations. You post them on LinkedIn yourself."
      >
        <button type="button" className="btn" onClick={() => setSuggesting(true)}>
          Suggest ideas
        </button>
        <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
          Add idea
        </button>
      </PageHeader>

      <ErrorNote>{week.error && errorMessage(week.error)}</ErrorNote>
      {!data && !week.error && <Loading label="Loading the week" />}

      {data && (
        <section className="panel" aria-labelledby="marketing-week">
          <div className="panel-head">
            <h2 id="marketing-week">
              Week of {formatDate(start)}{' '}
              <span className="panel-meta">
                {planned.length === 0
                  ? 'Nothing planned yet'
                  : `${data.posted} of ${plural(planned.length, 'post')} posted${data.ready > 0 ? `, ${data.ready} ready` : ''}`}
              </span>
            </h2>
            <span className="week-nav">
              <button type="button" className="btn btn-sm" onClick={() => goTo(addDays(start, -7))}>
                Previous week
              </button>
              {start !== thisWeek && (
                <button type="button" className="btn btn-sm" onClick={() => goTo(thisWeek)}>
                  This week
                </button>
              )}
              <button type="button" className="btn btn-sm" onClick={() => goTo(addDays(start, 7))}>
                Next week
              </button>
            </span>
          </div>

          <ol className="post-days">
            {days.map((day) => {
              const onDay = planned.filter((post) => post.plannedFor === day);
              return (
                <li key={day} className={day === today ? 'post-day post-day-today' : 'post-day'}>
                  <div className="post-day-head">
                    <span className="post-day-name">
                      {formatWeekday(day)}
                      {day === today && <span className="muted"> Today</span>}
                    </span>
                    <button
                      type="button"
                      className="btn btn-sm btn-quiet"
                      onClick={() => setEditing({ plannedFor: day })}
                      aria-label={`Plan a post for ${formatWeekday(day)}`}
                    >
                      Add
                    </button>
                  </div>
                  {onDay.length > 0 ? (
                    <ul className="post-cards">
                      {onDay.map((post) => (
                        <PostCard key={post.id} post={post} onOpen={() => setEditing(post)} />
                      ))}
                    </ul>
                  ) : (
                    <p className="post-day-empty muted">Nothing planned</p>
                  )}
                </li>
              );
            })}
          </ol>
        </section>
      )}

      {data && (
        <section className="panel" aria-labelledby="marketing-ideas">
          <div className="panel-head">
            <h2 id="marketing-ideas">
              Ideas <span className="panel-meta">Not on the calendar yet. Open one to give it a day.</span>
            </h2>
          </div>
          {ideas.length === 0 ? (
            <Empty title="No ideas waiting">Add one, or let AI suggest a few for the week.</Empty>
          ) : (
            <ul className="post-cards">
              {ideas.map((post) => (
                <PostCard key={post.id} post={post} onOpen={() => setEditing(post)} />
              ))}
            </ul>
          )}
          {ai.data && (
            <p className="panel-foot">
              {aiLine(ai.data)} <Link to="/prospects">AI settings</Link>
            </p>
          )}
        </section>
      )}

      {editing && (
        <Modal title={editing.id ? editing.title : 'Add a post'} onClose={() => setEditing(null)} wide>
          <PostEditor
            post={editing}
            onClose={() => setEditing(null)}
            onChanged={week.reload}
          />
        </Modal>
      )}

      {suggesting && (
        <Modal title="Suggest ideas" onClose={() => setSuggesting(false)}>
          <SuggestIdeas
            onCancel={() => setSuggesting(false)}
            onAdded={(count) => {
              setSuggesting(false);
              week.reload();
              toast(`Added ${plural(count, 'idea')}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}

function PostCard({ post, onOpen }) {
  const who = [POST_ACCOUNT[post.account], post.ownerName].filter(Boolean).join(', ');
  return (
    <li className="post-card">
      <button type="button" className="post-card-open" onClick={onOpen}>
        <span className="post-card-title">{post.title}</span>
        <span className="post-card-meta">
          <Pill value={post.status} labels={POST_STATUS} />
          {post.theme !== 'OTHER' && <span>{POST_THEME[post.theme]}</span>}
          <span>{who}</span>
          {post.status === 'POSTED' && post.conversations > 0 && <span>{plural(post.conversations, 'conversation')}</span>}
        </span>
      </button>
    </li>
  );
}

const asText = (value) => (value === null || value === undefined ? '' : String(value));
const asNumber = (value) => (value === '' ? null : Number(value));

/** Adds or changes a post, drafts it with AI, and records it as posted with its results. */
function PostEditor({ post, onClose, onChanged }) {
  const toast = useToast();
  const { user } = useAuth();
  const users = useUsers();
  const [saved, setSaved] = useState(post.id ? post : null);
  const [form, setForm] = useState(() => ({
    title: post.title ?? '',
    body: post.body ?? '',
    notes: post.notes ?? '',
    theme: post.theme ?? 'OTHER',
    account: post.account ?? 'PERSONAL',
    status: post.status ?? 'IDEA',
    plannedFor: post.plannedFor ?? '',
    // A new post is yours unless you pick someone else.
    ownerId: asText(post.id ? post.ownerId : user?.id),
  }));
  const [direction, setDirection] = useState('');
  const [drafted, setDrafted] = useState(null);
  const [postUrl, setPostUrl] = useState(post.postUrl ?? '');
  const [results, setResults] = useState(() => ({
    impressions: asText(post.impressions),
    reactions: asText(post.reactions),
    comments: asText(post.comments),
    conversations: asText(post.conversations),
  }));
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));
  const setResult = (field) => (value) => setResults((current) => ({ ...current, [field]: value }));
  const isPosted = saved?.status === 'POSTED';

  function payload() {
    return {
      title: form.title,
      body: form.body.trim() ? form.body : null,
      notes: form.notes.trim() ? form.notes : null,
      theme: form.theme,
      account: form.account,
      // Writing the post moves an idea on to a draft.
      status: form.status === 'IDEA' && form.body.trim() ? 'DRAFT' : form.status,
      plannedFor: form.plannedFor || null,
      ownerId: form.ownerId ? Number(form.ownerId) : null,
    };
  }

  /** Saves what's in the form and hands back the saved post. */
  async function persist() {
    const record = saved ? await api.marketing.posts.update(saved.id, payload()) : await api.marketing.posts.create(payload());
    setSaved(record);
    setForm((current) => ({ ...current, status: record.status, ownerId: asText(record.ownerId) }));
    onChanged();
    return record;
  }

  async function run(name, work) {
    setBusy(name);
    setError('');
    try {
      await work();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  function submit(event) {
    event.preventDefault();
    run('save', async () => {
      const record = await persist();
      toast(post.id ? 'Saved' : `Added ${record.title}`);
      onClose();
    });
  }

  function draftWithAi() {
    if (!form.title.trim()) {
      setError('Give the post a title first. The draft is written from it and the notes.');
      return;
    }
    run('draft', async () => {
      const record = await persist();
      const draft = await api.marketing.draft(record.id, { direction: direction.trim() || undefined });
      setForm((current) => ({ ...current, body: draft.body }));
      setDrafted(draft);
    });
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(form.body);
      toast('Copied. Paste it into LinkedIn.');
    } catch {
      setError('Couldn’t copy. Select the post and copy it yourself.');
    }
  }

  function markPosted() {
    run('posted', async () => {
      const record = await persist();
      const updated = await api.marketing.posted(record.id, { postUrl: postUrl.trim() || null });
      setSaved(updated);
      setForm((current) => ({ ...current, status: updated.status }));
      onChanged();
      toast('Marked as posted. Add its results in a few days.');
    });
  }

  function saveResults() {
    run('results', async () => {
      const updated = await api.marketing.results(saved.id, {
        impressions: asNumber(results.impressions),
        reactions: asNumber(results.reactions),
        comments: asNumber(results.comments),
        conversations: asNumber(results.conversations),
      });
      setSaved(updated);
      onChanged();
      toast('Results saved');
    });
  }

  const statuses = isPosted ? POST_STATUS : { IDEA: POST_STATUS.IDEA, DRAFT: POST_STATUS.DRAFT, READY: POST_STATUS.READY };
  const tooLong = form.body.length > POST_LIMIT;

  return (
    <form className="form post-form" onSubmit={submit}>
      <TextField label="Title" value={form.title} onChange={set('title')} required maxLength={TITLE_LIMIT} autoFocus={!post.id} />

      <div className="form-row">
        <TextField
          label="Day to post"
          type="date"
          value={form.plannedFor}
          onChange={set('plannedFor')}
          hint="Leave it empty to keep this as an idea."
        />
        <SelectField label="Status" value={form.status} onChange={set('status')} options={options(statuses)} required />
      </div>

      <div className="form-row">
        <SelectField label="Theme" value={form.theme} onChange={set('theme')} options={options(POST_THEME)} required />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} label="Who posts it" />
      </div>

      <fieldset className="field post-account">
        <legend>Goes on</legend>
        <span className="segmented segmented-small">
          {Object.entries(POST_ACCOUNT).map(([value, label]) => (
            <label key={value} className={form.account === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="post-account" checked={form.account === value} onChange={() => set('account')(value)} />
              {label}
            </label>
          ))}
        </span>
      </fieldset>

      <TextArea
        label="Notes"
        rows={3}
        value={form.notes}
        onChange={set('notes')}
        maxLength={2000}
        hint="Facts and points to make. AI drafts use only these and the title, and never name a client or candidate."
      />

      <div className="composer-draft">
        <TextField
          label="Direction for the draft (optional)"
          value={direction}
          onChange={setDirection}
          maxLength={1000}
          placeholder="For example: shorter, or open with the question"
          className="post-direction"
        />
        <button type="button" className="btn btn-sm" disabled={busy === 'draft'} onClick={draftWithAi}>
          {busy === 'draft' ? 'Drafting…' : drafted || form.body.trim() ? 'Redraft with AI' : 'Draft with AI'}
        </button>
      </div>
      {drafted && (
        <p className="field-hint">
          Drafted by {drafted.provider} for {dollars(drafted.costCents)}. Read it over, then save. Drafting saves the title and
          notes first.
        </p>
      )}

      <div className="field">
        <label htmlFor="post-body">Post</label>
        <textarea id="post-body" rows={10} value={form.body} onChange={(event) => set('body')(event.target.value)} />
        <p className={`field-hint ${tooLong ? 'composer-warn' : ''}`}>
          {form.body.length.toLocaleString()} of {POST_LIMIT.toLocaleString()} characters
          {form.body.trim() && (
            <>
              {' · '}
              <button type="button" className="link-button" onClick={copy}>
                Copy post
              </button>
            </>
          )}
        </p>
      </div>

      {saved && !isPosted && (
        <div className="post-step">
          <TextField
            label="LinkedIn link, once it’s up (optional)"
            type="url"
            value={postUrl}
            onChange={setPostUrl}
            maxLength={500}
            placeholder="https://www.linkedin.com/posts/…"
          />
          <button type="button" className="btn" disabled={busy === 'posted'} onClick={markPosted}>
            {busy === 'posted' ? 'Saving…' : 'Mark as posted'}
          </button>
        </div>
      )}

      {isPosted && (
        <fieldset className="post-results">
          <legend>
            Results{' '}
            <span className="muted">
              Posted {formatDate(saved.postedOn)}
              {saved.postUrl && (
                <>
                  {' · '}
                  <ExternalLink href={href(saved.postUrl)}>Open on LinkedIn</ExternalLink>
                </>
              )}
            </span>
          </legend>
          <div className="post-results-grid">
            <TextField label="Impressions" type="number" min="0" value={results.impressions} onChange={setResult('impressions')} />
            <TextField label="Reactions" type="number" min="0" value={results.reactions} onChange={setResult('reactions')} />
            <TextField label="Comments" type="number" min="0" value={results.comments} onChange={setResult('comments')} />
            <TextField
              label="Conversations"
              type="number"
              min="0"
              value={results.conversations}
              onChange={setResult('conversations')}
              hint="DMs or calls it led to"
            />
          </div>
          <div className="form-actions form-actions-start">
            <button type="button" className="btn" disabled={busy === 'results'} onClick={saveResults}>
              {busy === 'results' ? 'Saving…' : 'Save results'}
            </button>
          </div>
        </fieldset>
      )}

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {saved && (
          <span className="form-actions-left">
            <ConfirmButton
              className="btn btn-quiet"
              onConfirm={() =>
                run('delete', async () => {
                  await api.marketing.posts.remove(saved.id);
                  onChanged();
                  toast(`Deleted ${saved.title}`);
                  onClose();
                })
              }
            />
          </span>
        )}
        <button type="button" className="btn" onClick={onClose}>
          {saved && !post.id ? 'Close' : 'Cancel'}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy === 'save' || tooLong}>
          {busy === 'save' ? 'Saving…' : saved ? 'Save' : 'Add post'}
        </button>
      </div>
    </form>
  );
}

/** Asks AI for post ideas; the person adds the ones they like. */
function SuggestIdeas({ onCancel, onAdded }) {
  const [count, setCount] = useState('5');
  const [focus, setFocus] = useState('');
  const [result, setResult] = useState(null);
  const [picked, setPicked] = useState([]);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');

  async function suggest(event) {
    event.preventDefault();
    setBusy('suggest');
    setError('');
    try {
      const suggested = await api.marketing.suggest({ count: Number(count), focus: focus.trim() || null });
      setResult(suggested);
      setPicked(suggested.ideas);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  async function add() {
    setBusy('add');
    setError('');
    let added = 0;
    try {
      for (const idea of picked) {
        await api.marketing.posts.create({ title: idea.slice(0, TITLE_LIMIT), status: 'IDEA' });
        added += 1;
      }
      onAdded(added);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(null);
    }
  }

  return (
    <form className="form" onSubmit={suggest}>
      <div className="form-row">
        <SelectField
          label="How many"
          value={count}
          onChange={setCount}
          options={['3', '5', '8'].map((value) => ({ value, label: value }))}
          required
        />
        <TextField label="Focus (optional)" value={focus} onChange={setFocus} maxLength={500} placeholder="For example: FDE hiring at insurers" />
      </div>
      <p className="field-hint">Ideas mix FDE hiring, AI platform hiring, and AI and security at banks and insurers, and skip your recent posts.</p>

      {result && (
        <>
          <CheckboxGroup
            label="Pick the ones to add"
            options={result.ideas.map((idea) => ({ value: idea, label: idea }))}
            value={picked}
            onChange={setPicked}
          />
          <p className="field-hint">
            Suggested by {result.provider} for {dollars(result.costCents)}.
          </p>
        </>
      )}

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className={result ? 'btn' : 'btn btn-primary'} disabled={busy !== null}>
          {busy === 'suggest' ? 'Suggesting…' : result ? 'Suggest again' : 'Suggest'}
        </button>
        {result && (
          <button type="button" className="btn btn-primary" disabled={busy !== null || picked.length === 0} onClick={add}>
            {busy === 'add' ? 'Adding…' : `Add ${plural(picked.length, 'idea')}`}
          </button>
        )}
      </div>
    </form>
  );
}
