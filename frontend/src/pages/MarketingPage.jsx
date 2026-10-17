import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, Loading, PageHeader, Pill } from '../components/Bits.jsx';
import MarketingPlan from '../components/MarketingPlan.jsx';
import Modal from '../components/Modal.jsx';
import PostEditor from '../components/PostEditor.jsx';
import PostToLinkedIn from '../components/PostToLinkedIn.jsx';
import SetUpWeek from '../components/SetUpWeek.jsx';
import SuggestIdeas from '../components/SuggestIdeas.jsx';
import { formatDate, formatWeekday, plural, todayIso } from '../format.js';
import { useLoader, useTitle, useToast } from '../hooks.js';
import { POST_ACCOUNT, POST_STATUS, POST_THEME } from '../labels.js';
import { addDays, dayLabel, mondayOf, monthOf, postedAs } from '../marketing.js';

const VIEWS = [
  ['week', 'Week'],
  ['upcoming', 'Upcoming'],
  ['plan', 'Plan'],
];

/** "Drafts are written by Claude Sonnet 5.5.", or what's missing before drafting can work. */
function aiLine(ai) {
  if (!ai) return null;
  const current = ai.providers.find((provider) => provider.id === ai.provider);
  if (!current) return null;
  const model = current.models.find((option) => option.id === current.model)?.label ?? current.model;
  return current.hasKey ? `Drafts are written by ${model}.` : `Drafting needs a ${current.label} API key first.`;
}

/**
 * The team's LinkedIn posts: the week's calendar, everything coming up with its picture, and the
 * plan (style, posting days, monthly focus, LinkedIn connection) that AI drafts follow.
 */
export default function MarketingPage() {
  useTitle('Marketing');
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const today = todayIso();
  const thisWeek = mondayOf(today);
  const view = VIEWS.some(([value]) => value === params.get('view')) ? params.get('view') : 'week';
  const start = mondayOf(params.get('week') || today);
  const week = useLoader(() => api.marketing.week(start), [start], `marketing:week:${start}`);
  const upcoming = useLoader(() => api.marketing.upcoming(), [], 'marketing:upcoming');
  const ai = useLoader(() => api.ai.status(), [], 'ai:status');
  // A link like /marketing?post=12 (from Today) opens that post.
  const linkedId = params.get('post');
  const linked = useLoader(() => (linkedId ? api.marketing.posts.get(linkedId) : Promise.resolve(null)), [linkedId]);
  const [editing, setEditing] = useState(null);
  const [suggesting, setSuggesting] = useState(false);
  const [settingUp, setSettingUp] = useState(false);
  const plan = useLoader(
    () => (settingUp ? api.marketing.plan(monthOf(addDays(start, 3))) : Promise.resolve(null)),
    [settingUp, start],
  );

  function setParam(changes) {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)));
    setParams(next, { replace: true });
  }

  function reload() {
    week.reload();
    upcoming.reload();
  }

  const open = editing ?? (linkedId && linked.data?.id === Number(linkedId) ? linked.data : null);
  function closeEditor() {
    setEditing(null);
    if (linkedId) setParam({ post: null });
  }

  const outcome = params.get('linkedin');
  const canMakePictures = Boolean(ai.data?.providers.find((provider) => provider.id === 'OPENAI')?.hasKey);

  return (
    <>
      <PageHeader
        title="Marketing"
        sub="Plan the week’s LinkedIn posts, write them with AI in your style, and post them with their pictures."
      >
        <button type="button" className="btn" onClick={() => setSuggesting(true)}>
          Suggest ideas
        </button>
        <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
          Add idea
        </button>
      </PageHeader>

      <fieldset className="segmented marketing-views">
        <legend className="visually-hidden">View</legend>
        {VIEWS.map(([value, label]) => (
          <label key={value} className={view === value ? 'segment segment-on' : 'segment'}>
            <input type="radio" name="marketing-view" checked={view === value} onChange={() => setParam({ view: value === 'week' ? null : value })} />
            {label}
          </label>
        ))}
      </fieldset>

      {outcome && (
        <div className={outcome === 'connected' ? 'notice notice-good' : 'notice notice-bad'} role="status">
          <p>{outcome === 'connected' ? 'Your LinkedIn is connected. Post to LinkedIn now posts straight to your profile.' : params.get('reason') || 'LinkedIn didn’t connect. Try again.'}</p>
          <button type="button" className="btn btn-sm btn-quiet" onClick={() => setParam({ linkedin: null, reason: null })}>
            Dismiss
          </button>
        </div>
      )}

      {view === 'week' && (
        <WeekView
          week={week}
          start={start}
          today={today}
          thisWeek={thisWeek}
          onWeek={(monday) => setParam({ week: monday === thisWeek ? null : monday })}
          onOpen={setEditing}
          onSetUp={() => setSettingUp(true)}
          ai={ai.data}
        />
      )}

      {view === 'upcoming' && <UpcomingView upcoming={upcoming} today={today} onOpen={setEditing} onChanged={reload} />}

      {view === 'plan' && <MarketingPlan today={today} />}

      {open && (
        <Modal title={open.id ? open.title : 'Add a post'} onClose={closeEditor} wide>
          <PostEditor post={open} onClose={closeEditor} onChanged={reload} />
        </Modal>
      )}

      {suggesting && (
        <Modal title="Suggest ideas" onClose={() => setSuggesting(false)}>
          <SuggestIdeas
            onCancel={() => setSuggesting(false)}
            onAdded={(count) => {
              setSuggesting(false);
              reload();
              toast(`Added ${plural(count, 'idea')}`);
            }}
          />
        </Modal>
      )}

      {settingUp && (
        <Modal title={`Set up the week of ${formatDate(start)}`} onClose={() => setSettingUp(false)}>
          <SetUpWeek
            start={start}
            today={today}
            plan={plan.data}
            planned={week.data?.planned ?? []}
            canMakePictures={canMakePictures}
            onChanged={reload}
            onNextWeek={() => setParam({ week: addDays(start, 7) })}
            onClose={() => setSettingUp(false)}
          />
        </Modal>
      )}
    </>
  );
}

function WeekView({ week, start, today, thisWeek, onWeek, onOpen, onSetUp, ai }) {
  const data = week.data;
  const days = Array.from({ length: 7 }, (_, index) => addDays(start, index));
  const planned = data?.planned ?? [];
  const ideas = data?.ideas ?? [];

  if (!data) {
    return week.error ? <ErrorNote>{errorMessage(week.error)}</ErrorNote> : <Loading label="Loading the week" />;
  }

  return (
    <>
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
            {start >= thisWeek && (
              <button type="button" className="btn btn-sm btn-primary" onClick={onSetUp}>
                Set up the week
              </button>
            )}
            <button type="button" className="btn btn-sm" onClick={() => onWeek(addDays(start, -7))}>
              Previous week
            </button>
            {start !== thisWeek && (
              <button type="button" className="btn btn-sm" onClick={() => onWeek(thisWeek)}>
                This week
              </button>
            )}
            <button type="button" className="btn btn-sm" onClick={() => onWeek(addDays(start, 7))}>
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
                    onClick={() => onOpen({ plannedFor: day })}
                    aria-label={`Plan a post for ${formatWeekday(day)}`}
                  >
                    Add
                  </button>
                </div>
                {onDay.length > 0 ? (
                  <ul className="post-cards">
                    {onDay.map((post) => (
                      <PostCard key={post.id} post={post} onOpen={() => onOpen(post)} />
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
              <PostCard key={post.id} post={post} onOpen={() => onOpen(post)} />
            ))}
          </ul>
        )}
        {ai && (
          <p className="panel-foot">
            {aiLine(ai)} <Link to="/prospects">AI settings</Link>
          </p>
        )}
      </section>
    </>
  );
}

function PostCard({ post, onOpen }) {
  const who = [POST_ACCOUNT[post.account], post.ownerName].filter(Boolean).join(', ');
  return (
    <li className="post-card">
      <button type="button" className="post-card-open" onClick={onOpen}>
        {post.picture?.ready && <img className="post-card-picture" src={post.picture.url} alt="" loading="lazy" />}
        <span className="post-card-text">
          <span className="post-card-title">{post.title}</span>
          <span className="post-card-meta">
            <Pill value={post.status} labels={POST_STATUS} />
            {post.theme !== 'OTHER' && <span>{POST_THEME[post.theme]}</span>}
            <span>{who}</span>
            {post.status === 'POSTED' && post.conversations > 0 && <span>{plural(post.conversations, 'conversation')}</span>}
          </span>
        </span>
      </button>
    </li>
  );
}

/** Everything coming up, as it will look on LinkedIn, with a button to post each. */
function UpcomingView({ upcoming, today, onOpen, onChanged }) {
  const posts = upcoming.data;
  if (!posts) {
    return upcoming.error ? <ErrorNote>{errorMessage(upcoming.error)}</ErrorNote> : <Loading label="Loading upcoming posts" />;
  }
  if (posts.length === 0) {
    return (
      <section className="panel">
        <Empty title="Nothing coming up">Set up the week, or give an idea a day, and it shows here.</Empty>
      </section>
    );
  }
  return (
    <div className="post-feed">
      {posts.map((post) => (
        <PostPreview key={post.id} post={post} today={today} onOpen={() => onOpen(post)} onChanged={onChanged} />
      ))}
    </div>
  );
}

function PostPreview({ post, today, onOpen, onChanged }) {
  const missed = post.plannedFor < today;
  return (
    <article className={missed ? 'post-preview post-preview-missed' : 'post-preview'} aria-label={post.title}>
      <header className="post-preview-head">
        <span className="post-preview-day">{missed ? `Missed, ${formatWeekday(post.plannedFor)}` : dayLabel(post.plannedFor, today)}</span>
        <Pill value={post.status} labels={POST_STATUS} />
      </header>
      <h3 className="post-preview-title">{post.title}</h3>
      <p className="post-preview-who">{postedAs(post)}</p>
      {post.body ? (
        <p className="post-preview-text">{post.body}</p>
      ) : (
        <p className="post-preview-text muted">Not written yet.</p>
      )}
      {post.picture &&
        (post.picture.ready ? (
          <img className="post-preview-picture" src={post.picture.url} alt="" loading="lazy" />
        ) : (
          <div className="post-preview-picture post-preview-wait">The picture is on its way from another computer.</div>
        ))}
      <footer className="post-preview-actions">
        <PostToLinkedIn post={post} onPosted={onChanged} size="sm" />
        <button type="button" className="btn btn-sm btn-quiet" onClick={onOpen}>
          {post.body ? 'Open' : 'Write it'}
        </button>
      </footer>
    </article>
  );
}
