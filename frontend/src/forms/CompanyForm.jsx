import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { CheckboxGroup, OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import {
  COMPANY_STATUS,
  HIRING_NEED,
  INDUSTRY,
  LEAD_SOURCE,
  PRIORITY,
  SEGMENT,
  TIER,
  TIMELINE,
  options,
} from '../labels.js';
import { useAuth, useUsers } from '../hooks.js';
import { asForm, blankToNull, toId, toNumber } from './formUtils.js';

const FIELDS = {
  name: '',
  website: '',
  location: '',
  companySize: '',
  status: 'LEAD',
  focus: '',
  industry: '',
  segments: [],
  tier: '',
  priority: '',
  hiringNeed: '',
  hiringTimeline: '',
  anticipatedHires: '',
  leadSource: '',
  lastContactedDate: '',
  followUpDate: '',
  offLimitsUntil: '',
  linkedinProfile: '',
  notes: '',
  ownerId: '',
};

export default function CompanyForm({ company, onSaved, onCancel }) {
  const { user } = useAuth();
  const users = useUsers();
  const [form, setForm] = useState(() => {
    const initial = asForm(company, FIELDS);
    if (!company?.id && !initial.ownerId && user) initial.ownerId = String(user.id);
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
      website: blankToNull(form.website),
      location: blankToNull(form.location),
      companySize: blankToNull(form.companySize),
      status: form.status || null,
      focus: blankToNull(form.focus),
      industry: blankToNull(form.industry),
      segments: form.segments,
      tier: blankToNull(form.tier),
      priority: blankToNull(form.priority),
      hiringNeed: blankToNull(form.hiringNeed),
      hiringTimeline: blankToNull(form.hiringTimeline),
      anticipatedHires: toNumber(form.anticipatedHires),
      leadSource: blankToNull(form.leadSource),
      lastContactedDate: blankToNull(form.lastContactedDate),
      followUpDate: blankToNull(form.followUpDate),
      offLimitsUntil: blankToNull(form.offLimitsUntil),
      linkedinProfile: blankToNull(form.linkedinProfile),
      notes: blankToNull(form.notes),
      ownerId: toId(form.ownerId),
    };

    try {
      const saved = company?.id ? await api.companies.update(company.id, body) : await api.companies.create(body);
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <TextField label="Company name" value={form.name} onChange={set('name')} required maxLength={255} autoFocus />
      <TextField
        label="Focus"
        value={form.focus}
        onChange={set('focus')}
        placeholder="AI fraud detection for regional banks"
        hint="What they sell and to which institutions."
        maxLength={500}
      />
      <div className="form-row">
        <SelectField label="Stage" value={form.status} onChange={set('status')} options={options(COMPANY_STATUS)} required />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} />
      </div>
      <div className="form-row">
        <SelectField label="Priority" value={form.priority} onChange={set('priority')} options={options(PRIORITY)} placeholder="Not set" />
        <SelectField label="Tier" value={form.tier} onChange={set('tier')} options={options(TIER)} placeholder="Not set" />
      </div>
      <div className="form-row">
        <TextField label="Follow up on" type="date" value={form.followUpDate} onChange={set('followUpDate')} />
        <TextField label="Last contacted" type="date" value={form.lastContactedDate} onChange={set('lastContactedDate')} />
      </div>

      <h3 className="form-section">Who they serve</h3>
      <div className="form-row">
        <SelectField label="Industry" value={form.industry} onChange={set('industry')} options={options(INDUSTRY)} placeholder="Not set" />
        <SelectField label="Lead source" value={form.leadSource} onChange={set('leadSource')} options={options(LEAD_SOURCE)} placeholder="Not set" />
      </div>
      <CheckboxGroup label="Segments" options={options(SEGMENT)} value={form.segments} onChange={set('segments')} />

      <h3 className="form-section">Hiring</h3>
      <div className="form-row">
        <SelectField label="Hiring need" value={form.hiringNeed} onChange={set('hiringNeed')} options={options(HIRING_NEED)} placeholder="Not set" />
        <SelectField
          label="Hiring timeline"
          value={form.hiringTimeline}
          onChange={set('hiringTimeline')}
          options={options(TIMELINE)}
          placeholder="Not set"
        />
      </div>
      <div className="form-row">
        <TextField
          label="Anticipated hires"
          type="number"
          min="0"
          inputMode="numeric"
          value={form.anticipatedHires}
          onChange={set('anticipatedHires')}
        />
        <TextField label="Company size" value={form.companySize} onChange={set('companySize')} placeholder="51-200" maxLength={100} />
      </div>

      <h3 className="form-section">Details</h3>
      <div className="form-row">
        <TextField label="Website" value={form.website} onChange={set('website')} placeholder="example.com" maxLength={500} />
        <TextField
          label="LinkedIn page"
          value={form.linkedinProfile}
          onChange={set('linkedinProfile')}
          placeholder="linkedin.com/company/example"
          maxLength={500}
        />
      </div>
      <div className="form-row">
        <TextField label="Location" value={form.location} onChange={set('location')} placeholder="New York, NY" maxLength={255} />
        <TextField
          label="Off-limits until"
          type="date"
          value={form.offLimitsUntil}
          onChange={set('offLimitsUntil')}
          hint="Blank means a year after the last placement starts. Pick today to end it."
        />
      </div>
      <TextArea
        label="Notes"
        value={form.notes}
        onChange={set('notes')}
        rows={6}
        maxLength={20000}
        hint="Discovery notes, contract terms, internal context. Stored encrypted."
      />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save company'}
        </button>
      </div>
    </form>
  );
}
