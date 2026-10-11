import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { OwnerField, SelectField, TextArea, TextField } from '../components/Fields.jsx';
import { ACTION_TYPE, OUTREACH_STATUS, options } from '../labels.js';
import { useAuth, useLoader, useUsers } from '../hooks.js';
import { asForm, blankToNull, toId } from './formUtils.js';

const FIELDS = {
  name: '',
  title: '',
  email: '',
  phone: '',
  linkedinUrl: '',
  status: 'NEW',
  lastContactedDate: '',
  nextActionDate: '',
  nextActionType: 'EMAIL',
  notes: '',
  companyId: '',
  ownerId: '',
};

/** `contact` may be a full record (edit) or just { companyId } to preset the company. */
export default function ContactForm({ contact, onSaved, onCancel }) {
  const { user } = useAuth();
  const users = useUsers();
  const companies = useLoader(() => api.companies.list(), []);
  const [form, setForm] = useState(() => {
    const initial = asForm(contact, FIELDS);
    if (!contact?.id && !initial.ownerId && user) initial.ownerId = String(user.id);
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
      title: blankToNull(form.title),
      email: blankToNull(form.email),
      phone: blankToNull(form.phone),
      linkedinUrl: blankToNull(form.linkedinUrl),
      status: form.status || null,
      lastContactedDate: form.lastContactedDate || null,
      nextActionDate: form.nextActionDate || null,
      nextActionType: form.nextActionDate ? form.nextActionType || 'EMAIL' : null,
      notes: blankToNull(form.notes),
      companyId: toId(form.companyId),
      ownerId: toId(form.ownerId),
    };

    try {
      const saved = contact?.id ? await api.contacts.update(contact.id, body) : await api.contacts.create(body);
      onSaved(saved);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const companyOptions = (companies.data ?? []).map((company) => ({ value: String(company.id), label: company.name }));

  return (
    <form className="form" onSubmit={submit}>
      <div className="form-row">
        <TextField label="Contact name" value={form.name} onChange={set('name')} required maxLength={255} autoFocus />
        <TextField label="Title" value={form.title} onChange={set('title')} placeholder="VP Engineering" maxLength={255} />
      </div>
      <SelectField
        label="Company"
        value={form.companyId}
        onChange={set('companyId')}
        options={companyOptions}
        placeholder={companies.loading ? 'Loading companies…' : 'Select a company'}
        required
      />
      <div className="form-row">
        <TextField label="Email" type="email" value={form.email} onChange={set('email')} maxLength={255} />
        <TextField label="Phone" type="tel" value={form.phone} onChange={set('phone')} maxLength={50} />
      </div>
      <TextField
        label="LinkedIn profile"
        value={form.linkedinUrl}
        onChange={set('linkedinUrl')}
        placeholder="linkedin.com/in/name"
        maxLength={500}
      />
      <div className="form-row">
        <SelectField label="Outreach status" value={form.status} onChange={set('status')} options={options(OUTREACH_STATUS)} required />
        <OwnerField users={users} value={form.ownerId} onChange={set('ownerId')} />
      </div>
      <div className="form-row">
        <TextField label="Next action date" type="date" value={form.nextActionDate} onChange={set('nextActionDate')} />
        <SelectField
          label="Next action type"
          value={form.nextActionType}
          onChange={set('nextActionType')}
          options={options(ACTION_TYPE)}
          required
        />
      </div>
      <TextField label="Last contacted" type="date" value={form.lastContactedDate} onChange={set('lastContactedDate')} />
      <TextArea label="Notes" value={form.notes} onChange={set('notes')} rows={4} maxLength={2000} />

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : 'Save contact'}
        </button>
      </div>
    </form>
  );
}
