import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { formatWeekday, plural } from '../format.js';
import { POST_THEME } from '../labels.js';
import { WEEKDAYS, addDays, dollars } from '../marketing.js';
import { ErrorNote } from './Bits.jsx';
import { TextArea } from './Fields.jsx';

/** Which of the plan's posting days in this week are still open, as the server decides it. */
function openings(plan, start, today, planned) {
  return (plan?.rhythm ?? []).map((slot) => {
    const date = addDays(start, WEEKDAYS.indexOf(slot.day));
    const taken = planned.some(
      (post) =>
        post.plannedFor === date &&
        post.account === slot.account &&
        (slot.account === 'COMPANY_PAGE' || (post.ownerId ?? null) === (slot.ownerId ?? null)),
    );
    return { slot, date, state: date < today ? 'past' : taken ? 'taken' : 'open' };
  });
}

/**
 * Writes a draft for every open posting day of the week in one go, from the plan, then offers to
 * make a picture for each. Everything lands on the calendar as drafts to review.
 */
export default function SetUpWeek({ start, today, plan, planned, canMakePictures, onChanged, onNextWeek, onClose }) {
  const [focus, setFocus] = useState('');
  const [result, setResult] = useState(null);
  const [pictures, setPictures] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');
  const days = openings(plan, start, today, planned);
  const open = days.filter((day) => day.state === 'open');
  const allPast = days.length > 0 && days.every((day) => day.state === 'past');

  async function write() {
    setBusy('write');
    setError('');
    try {
      setResult(await api.marketing.setUpWeek({ date: start, focus: focus.trim() || null }));
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  /** One picture at a time, so each shows up as it's made and a failure stops the rest. */
  async function makePictures() {
    setBusy('pictures');
    setError('');
    let cents = 0;
    try {
      for (let index = 0; index < result.created.length; index += 1) {
        setPictures({ done: index, cents });
        const made = await api.marketing.generatePicture(result.created[index].id, {});
        cents += made.costCents;
        onChanged();
      }
      setPictures({ done: result.created.length, cents, finished: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  if (!plan) return <p className="loading">Loading the plan…</p>;

  if (plan.rhythm.length === 0) {
    return (
      <div className="form">
        <p>The week is set up from your posting days: which days, who posts, and what about.</p>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
          <Link to="/marketing?view=plan" className="btn btn-primary" onClick={onClose}>
            Set your posting days
          </Link>
        </div>
      </div>
    );
  }

  if (result) {
    const total = result.created.length;
    return (
      <div className="form">
        <p>
          Wrote {plural(total, 'draft')} with {result.provider} for {dollars(result.costCents)}. They’re on the calendar as drafts to
          read over.
        </p>
        <ul className="setup-list">
          {result.created.map((post) => (
            <li key={post.id}>
              <strong>{formatWeekday(post.plannedFor)}</strong> {post.title}
            </li>
          ))}
        </ul>
        {canMakePictures ? (
          <p className="field-hint">
            {pictures?.finished
              ? `Made ${plural(total, 'picture')} for ${dollars(pictures.cents)}.`
              : busy === 'pictures'
                ? `Making picture ${pictures.done + 1} of ${total}…`
                : 'Make a picture for each with AI, in your picture style. Each takes a little while.'}
          </p>
        ) : (
          <p className="field-hint">To make pictures with AI, add an OpenAI key in the AI settings on AI Found.</p>
        )}
        <ErrorNote>{error}</ErrorNote>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy === 'pictures'}>
            Done
          </button>
          {canMakePictures && !pictures?.finished && (
            <button type="button" className="btn btn-primary" disabled={busy === 'pictures'} onClick={makePictures}>
              {busy === 'pictures' ? 'Making pictures…' : `Make ${plural(total, 'picture')}`}
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="form">
      <p>AI writes a draft for each open posting day below, in your style and this month’s focus. You read and edit each one.</p>
      <ul className="setup-list">
        {days.map(({ slot, date, state }) => (
          <li key={`${slot.day}-${slot.account}-${slot.ownerId}`} className={state === 'open' ? '' : 'muted'}>
            <strong>{formatWeekday(date)}</strong> {slot.account === 'COMPANY_PAGE' ? 'Company page' : (slot.ownerName ?? 'Unassigned')}
            {slot.theme !== 'OTHER' ? `, ${POST_THEME[slot.theme]}` : ''}
            {state === 'past' && ' (already past)'}
            {state === 'taken' && ' (already has a post)'}
          </li>
        ))}
      </ul>
      <TextArea
        label="Anything this week should be about (optional)"
        rows={2}
        value={focus}
        onChange={setFocus}
        maxLength={1000}
        placeholder="For example: we're at a fintech conference on Thursday"
      />
      {allPast && <p className="field-hint">This week’s posting days are past. Set up next week instead.</p>}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        {allPast && (
          <button type="button" className="btn btn-primary" onClick={onNextWeek}>
            Set up next week
          </button>
        )}
        <button
          type="button"
          className="btn btn-primary"
          hidden={allPast}
          disabled={busy === 'write' || open.length === 0}
          onClick={write}
        >
          {busy === 'write'
            ? 'Writing…'
            : open.length === 0
              ? 'Nothing left to write'
              : `Write ${plural(open.length, 'draft')}`}
        </button>
      </div>
    </div>
  );
}
