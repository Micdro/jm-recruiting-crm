import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import { ENGAGEMENT_TYPE, SEARCH_STATUS, TIMELINE, TRACK, WORK_MODEL, options } from '../labels.js';
import { useAuth, useLoader, useUsers } from '../hooks.js';
import { asForm, blankToNull, toId, toNumber } from './formUtils.js';

const FIELDS = {
  companyId: '',
  hiringManagerId: '',
  title: '',
  track: 'PRODUCTION_AI_ENGINEERING',
  status: 'INTAKE',
  engagementType: 'CONTINGENCY',
  feePercent: '',
  baseMin: '',
  baseMax: '',
  location: '',
  workModel: '',
  timeline: '',
  openedDate: '',
  productionOutcome: '',
  technicalScope: '',
  accountability: '',
  regulatoryConstraints: '',
  notes: '',
  ownerId: '',
};

/** Opening a search follows the intake call: the role, the terms, then the brief. */
export default function SearchForm({ search, onSaved, onCancel }) {
  const { user } = useAuth();
  const users = useUsers();
  const companies = useLoader(() => api.companies.list(), []);
  const [form, setForm] = useState(() => {
    const initial = asForm(search, FIELDS);
    if (!search?.id && !initial.ownerId && user) initial.ownerId = String(user.id);
    return initial;
  });
  const contacts = useLoader(
    () => (form.companyId ? api.contacts.list({ companyId: form.companyId }) : Promise.resolve([])),
    [form.companyId],
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');

    const body = {
      companyId: toId(form.companyId),
      hiringManagerId: toId(form.hiringManagerId),
      title: form.title.trim(),
      track: form.track,
      status: form.status || null,
      engagementType: form.engagementType || null,
      feePercent: toNumber(form.feePercent),
      baseMin: toNumber(form.baseMin),
      baseMax: toNumber(form.baseMax),
      location: blankToNull(form.location),
      workModel: form.workModel || null,
      timeline: form.timeline || null,
      openedDate: form.openedDate || null,
      productionOutcome: blankToNull(form.productionOutcome),
      technicalScope: blankToNull(form.technicalScope),
      accountability: blankToNull(form.accountability),
      regulatoryConstraints: blankToNull(form.regulatoryConstraints),
      notes: blankToNull(form.notes),
      ownerId: toId(form.ownerId),
    };

    try {
      const saved = search?.id ? await api.searches.update(search.id, body) : await api.searches.create(body);
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <h3 className="form-section">The role</h3>
      <TextField
        label="Role title"
        value={form.title}
        onChange={set('title')}
        placeholder="Forward Deployed Engineer"
        required
        maxLength={255}
        autoFocus
      />
      <div className="form-row">
        <SelectField
          label="Company"
          value={form.companyId}
          onChange={(value) => setForm((current) => ({ ...current, companyId: value, hiringManagerId: '' }))}
          options={(companies.data ?? []).map((company) => ({ value: String(company.id), label: company.name }))}
          placeholder={companies.loading ? 'Loading companies…' : 'Select a company'}
          required
        />
        <SelectField
          label="Hiring manager"
          value={form.hiringManagerId}
          onChange={set('hiringManagerId')}
          options={(contacts.data ?? []).map((contact) => ({ value: String(contact.id), label: contact.name }))}
          placeholder={form.companyId ? 'Not set' : 'Choose a company first'}
        />
      </div>
      <div className="form-row">
        <SelectField label="Track" value={form.track} onChange={set('track')} options={options(TRACK)} required />
        <SelectField label="Status" value={form.status} onChange={set('status')} options={options(SEARCH_STATUS)} required />
      </div>
      <div className="form-row">
        <TextField label="Location" value={form.location} onChange={set('location')} placeholder="New York or remote" maxLength={255} />
        <SelectField label="Work model" value={form.workModel} onChange={set('workModel')} options={options(WORK_MODEL)} placeholder="Not set" />
      </div>
      <div className="form-row">
        <TextField label="Base salary from" inputMode="numeric" value={form.baseMin} onChange={set('baseMin')} placeholder="180000" />
        <TextField label="Base salary to" inputMode="numeric" value={form.baseMax} onChange={set('baseMax')} placeholder="230000" />
      </div>

      <h3 className="form-section">Terms</h3>
      <div className="form-row">
        <SelectField
          label="Engagement"
          value={form.engagementType}
          onChange={set('engagementType')}
          options={options(ENGAGEMENT_TYPE)}
          required
        />
        <TextField
          label="Fee (% of first-year base)"
          inputMode="decimal"
          value={form.feePercent}
          onChange={set('feePercent')}
          placeholder="25"
        />
      </div>
      <div className="form-row">
        <SelectField label="Timeline" value={form.timeline} onChange={set('timeline')} options={options(TIMELINE)} placeholder="Not set" />
        <TextField label="Opened" type="date" value={form.openedDate} onChange={set('openedDate')} hint="Defaults to today." />
      </div>
      <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} label="Lead" />

      <h3 className="form-section">Search brief</h3>
      <TextArea
        label="Production outcome"
        value={form.productionOutcome}
        onChange={set('productionOutcome')}
        placeholder="What must be live and working because this person was hired"
        maxLength={2000}
      />
      <TextArea
        label="Technical scope"
        value={form.technicalScope}
        onChange={set('technicalScope')}
        placeholder="Systems, stack and customer environments"
        maxLength={2000}
      />
      <TextArea
        label="Accountability"
        value={form.accountability}
        onChange={set('accountability')}
        placeholder="What they will own, decide and answer for"
        maxLength={2000}
      />
      <TextArea
        label="Regulatory constraints"
        value={form.regulatoryConstraints}
        onChange={set('regulatoryConstraints')}
        placeholder="Model risk, data residency, audit, vendor due diligence"
        maxLength={2000}
      />
      <TextArea label="Notes" value={form.notes} onChange={set('notes')} maxLength={2000} />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : search?.id ? 'Save search' : 'Open search'}
        </button>
      </div>
    </form>
  );
}
