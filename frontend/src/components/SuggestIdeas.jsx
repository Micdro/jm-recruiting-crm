import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { plural } from '../format.js';
import { TITLE_LIMIT, dollars } from '../marketing.js';
import { ErrorNote } from './Bits.jsx';
import { CheckboxGroup, SelectField, TextField } from './Fields.jsx';

/** Asks AI for post ideas; the person adds the ones they like. */
export default function SuggestIdeas({ onCancel, onAdded }) {
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
