import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { mentions, profileReference, scrub } from '../blindProfile.js';
import { todayIso } from '../format.js';
import { useLoader } from '../hooks.js';
import { EMPLOYMENT_TYPE, EXPERIENCE, ROLE_AREA, TRACK, WORK_MODEL } from '../labels.js';
import { ErrorNote } from './Bits.jsx';
import { TextArea, TextField } from './Fields.jsx';

/**
 * Prepares a profile of a candidate to send a client without their name, contact details or
 * employer. Everything is filled in with those details taken out, for a final read before the
 * PDF is made; anything that still identifies them is pointed out.
 */
export default function BlindProfileForm({ candidate, entries, onDone, onCancel }) {
  const firm = useLoader(() => api.firm.invoiceSettings(), [], 'firm:invoice');
  const reference = profileReference(candidate.id);
  const searches = entries ?? [];
  const [entryId, setEntryId] = useState(searches.length === 1 ? String(searches[0].id) : '');
  const entry = searches.find((item) => String(item.id) === entryId) ?? null;
  const [form, setForm] = useState(() => ({
    title: candidate.currentTitle ?? '',
    employer: '',
    location: candidate.location ?? '',
    includeCompensation: false,
    evidence: scrub(candidate.productionEvidence, candidate),
    why: scrub(searches.length === 1 ? searches[0].rationale : '', candidate),
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  const facts = [
    ['Track', TRACK[candidate.track]],
    ['Experience', EXPERIENCE[candidate.experience]],
    ['Focus', (candidate.roleAreas ?? []).map((area) => ROLE_AREA[area]).join(', ')],
    ['Works', WORK_MODEL[candidate.workPreference]],
    ['Employment', EMPLOYMENT_TYPE[candidate.employmentType]],
    ['Relocation', candidate.willingToRelocate ? 'Open to relocating' : null],
    ['Regulated financial services', candidate.regulatedExperience ? 'Yes' : null],
    ['Compensation', form.includeCompensation ? candidate.compExpectation : null],
  ];
  const stillThere = mentions(
    [form.title, form.employer, form.location, form.evidence, form.why, ...facts.map(([, value]) => value)],
    candidate,
  );

  async function download(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const { downloadProfile } = await import('../blindProfilePdf.js');
      await downloadProfile({
        firm: firm.data ?? { firmName: 'Jane Michael LLC' },
        profile: {
          reference,
          date: todayIso(),
          preparedFor: entry ? `${entry.companyName}, ${entry.searchTitle}` : '',
          title: form.title,
          employer: form.employer,
          location: form.location,
          facts,
          evidence: form.evidence,
          why: form.why,
        },
      });
      // A note of where the profile went, in the candidate's history.
      await api.candidates.logActivity(candidate.id, {
        type: 'NOTE',
        notes: `Blind profile ${reference} prepared${entry ? ` for ${entry.companyName}, ${entry.searchTitle}` : ''}.`,
        searchId: entry?.searchId ?? null,
      });
      onDone(reference);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form blind-form" onSubmit={download}>
      <p className="form-intro">
        Reference <strong>{reference}</strong>. Their name, email, phone, LinkedIn and current employer are left out. Read it
        through before you send it.
      </p>

      {searches.length > 0 && (
        <div className="field">
          <label htmlFor="blind-search">For</label>
          <select
            id="blind-search"
            value={entryId}
            onChange={(event) => {
              const next = searches.find((item) => String(item.id) === event.target.value);
              setEntryId(event.target.value);
              set('why')(scrub(next?.rationale ?? '', candidate));
            }}
          >
            <option value="">No particular search</option>
            {searches.map((item) => (
              <option key={item.id} value={String(item.id)}>
                {item.companyName}, {item.searchTitle}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="form-row">
        <TextField label="Headline" value={form.title} onChange={set('title')} maxLength={200} placeholder="Staff ML Platform Engineer" />
        <TextField label="Location" value={form.location} onChange={set('location')} maxLength={200} hint="A metro area is safer than a town." />
      </div>
      <TextField
        label="Current employer, described"
        value={form.employer}
        onChange={set('employer')}
        maxLength={200}
        placeholder="a top-10 US card issuer"
        hint="Describe it without naming it, or leave it blank."
      />
      <TextArea label="What they have owned in production" rows={6} value={form.evidence} onChange={set('evidence')} maxLength={4000} />
      <TextArea
        label="Why we are presenting them"
        rows={3}
        value={form.why}
        onChange={set('why')}
        maxLength={2000}
        hint={entry ? 'From your rationale for this search.' : 'Optional.'}
      />
      {candidate.compExpectation && (
        <label className="check">
          <input
            type="checkbox"
            checked={form.includeCompensation}
            onChange={(event) => set('includeCompensation')(event.target.checked)}
          />{' '}
          Include their compensation expectation ({candidate.compExpectation})
        </label>
      )}

      {stillThere.length > 0 && (
        <p className="warn-note">
          It still mentions {stillThere.join(' and ')}. Take that out, or download it anyway if you mean to share it.
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Making the PDF…' : stillThere.length > 0 ? 'Download PDF anyway' : 'Download PDF'}
        </button>
      </div>
    </form>
  );
}
