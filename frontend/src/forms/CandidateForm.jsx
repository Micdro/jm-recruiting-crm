import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { CheckboxField, CheckboxGroup, OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import {
  ACTION_TYPE,
  CANDIDATE_SOURCE,
  CANDIDATE_STATUS,
  EMPLOYMENT_TYPE,
  EXPERIENCE,
  ROLE_AREA,
  TRACK,
  WORK_MODEL,
  options,
} from '../labels.js';
import { useAuth, useUsers } from '../hooks.js';
import { asForm, blankToNull, toId } from './formUtils.js';

const FIELDS = {
  name: '',
  email: '',
  phone: '',
  linkedinUrl: '',
  location: '',
  currentTitle: '',
  currentCompany: '',
  track: '',
  status: 'ACTIVE',
  source: '',
  regulatedExperience: false,
  roleAreas: [],
  experience: '',
  workPreference: '',
  employmentType: '',
  willingToRelocate: false,
  compExpectation: '',
  resumeUrl: '',
  productionEvidence: '',
  lastContactedDate: '',
  nextActionDate: '',
  nextActionType: 'LINKEDIN',
  notes: '',
  ownerId: '',
};

export default function CandidateForm({ candidate, onSaved, onCancel }) {
  const { user } = useAuth();
  const users = useUsers();
  const [form, setForm] = useState(() => {
    const initial = asForm(candidate, FIELDS);
    if (!candidate?.id && !initial.ownerId && user) initial.ownerId = String(user.id);
    return initial;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');

    const body = {
      name: form.name.trim(),
      email: blankToNull(form.email),
      phone: blankToNull(form.phone),
      linkedinUrl: blankToNull(form.linkedinUrl),
      location: blankToNull(form.location),
      currentTitle: blankToNull(form.currentTitle),
      currentCompany: blankToNull(form.currentCompany),
      track: form.track || null,
      status: form.status || null,
      source: form.source || null,
      regulatedExperience: !!form.regulatedExperience,
      roleAreas: form.roleAreas,
      experience: blankToNull(form.experience),
      workPreference: blankToNull(form.workPreference),
      employmentType: blankToNull(form.employmentType),
      willingToRelocate: !!form.willingToRelocate,
      compExpectation: blankToNull(form.compExpectation),
      resumeUrl: blankToNull(form.resumeUrl),
      productionEvidence: blankToNull(form.productionEvidence),
      lastContactedDate: form.lastContactedDate || null,
      nextActionDate: form.nextActionDate || null,
      nextActionType: form.nextActionDate ? form.nextActionType || 'LINKEDIN' : null,
      notes: blankToNull(form.notes),
      ownerId: toId(form.ownerId),
    };

    try {
      const saved = candidate?.id ? await api.candidates.update(candidate.id, body) : await api.candidates.create(body);
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <div className="form-row">
        <TextField label="Candidate name" value={form.name} onChange={set('name')} required maxLength={255} autoFocus />
        <TextField label="Location" value={form.location} onChange={set('location')} maxLength={255} />
      </div>
      <div className="form-row">
        <TextField label="Current title" value={form.currentTitle} onChange={set('currentTitle')} maxLength={255} />
        <TextField label="Current company" value={form.currentCompany} onChange={set('currentCompany')} maxLength={255} />
      </div>
      <div className="form-row">
        <SelectField label="Track" value={form.track} onChange={set('track')} options={options(TRACK)} placeholder="Not set" />
        <SelectField label="Status" value={form.status} onChange={set('status')} options={options(CANDIDATE_STATUS)} required />
      </div>
      <TextArea
        label="Production evidence"
        value={form.productionEvidence}
        onChange={set('productionEvidence')}
        rows={4}
        maxLength={10000}
        placeholder="Systems they owned in production, decisions they made, incidents they handled"
        hint="Accountability over titles: what they were answerable for, with specifics."
      />
      <CheckboxField
        label="Has built or run production systems for banks, insurers or other regulated institutions"
        checked={form.regulatedExperience}
        onChange={set('regulatedExperience')}
      />
      <CheckboxGroup
        label="Applicable roles"
        options={options(ROLE_AREA)}
        value={form.roleAreas}
        onChange={set('roleAreas')}
        hint="Which part of production AI they cover."
      />
      <div className="form-row">
        <SelectField label="Experience" value={form.experience} onChange={set('experience')} options={options(EXPERIENCE)} placeholder="Not set" />
        <SelectField
          label="Employment"
          value={form.employmentType}
          onChange={set('employmentType')}
          options={options(EMPLOYMENT_TYPE)}
          placeholder="Not set"
        />
      </div>
      <div className="form-row">
        <SelectField
          label="Work preference"
          value={form.workPreference}
          onChange={set('workPreference')}
          options={options(WORK_MODEL)}
          placeholder="Not set"
        />
        <CheckboxField label="Willing to relocate" checked={form.willingToRelocate} onChange={set('willingToRelocate')} />
      </div>
      <div className="form-row">
        <TextField label="Email" type="email" value={form.email} onChange={set('email')} maxLength={255} />
        <TextField label="Phone" type="tel" value={form.phone} onChange={set('phone')} maxLength={50} />
      </div>
      <div className="form-row">
        <TextField label="LinkedIn profile" value={form.linkedinUrl} onChange={set('linkedinUrl')} maxLength={500} />
        <TextField label="Resume link" value={form.resumeUrl} onChange={set('resumeUrl')} placeholder="Drive or Dropbox link" maxLength={500} />
      </div>
      <div className="form-row">
        <TextField
          label="Compensation expectation"
          value={form.compExpectation}
          onChange={set('compExpectation')}
          placeholder="$220k base plus equity"
          maxLength={255}
        />
        <SelectField label="Source" value={form.source} onChange={set('source')} options={options(CANDIDATE_SOURCE)} placeholder="Not set" />
      </div>
      <div className="form-row">
        <TextField label="Next action date" type="date" value={form.nextActionDate} onChange={set('nextActionDate')} />
        <SelectField label="Next action type" value={form.nextActionType} onChange={set('nextActionType')} options={options(ACTION_TYPE)} required />
      </div>
      <div className="form-row">
        <TextField label="Last contacted" type="date" value={form.lastContactedDate} onChange={set('lastContactedDate')} />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} />
      </div>
      <TextArea label="Notes" value={form.notes} onChange={set('notes')} rows={4} maxLength={10000} />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save candidate'}
        </button>
      </div>
    </form>
  );
}
