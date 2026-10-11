import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import { COMPANY_STATUS, options } from '../labels.js';
import { useAuth, useUsers } from '../hooks.js';
import { asForm, blankToNull, toId } from './formUtils.js';

const FIELDS = {
  name: '',
  website: '',
  location: '',
  companySize: '',
  status: 'TARGET',
  focus: '',
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
        <SelectField label="Status" value={form.status} onChange={set('status')} options={options(COMPANY_STATUS)} required />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} />
      </div>
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
        <TextField label="Company size" value={form.companySize} onChange={set('companySize')} placeholder="51-200" maxLength={100} />
      </div>
      <TextArea label="Notes" value={form.notes} onChange={set('notes')} rows={4} maxLength={2000} />

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
