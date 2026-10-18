import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { useToast } from '../hooks.js';
import { POST_ACCOUNT, POST_THEME, options } from '../labels.js';
import { POST_LIMIT, TITLE_LIMIT, usePostTemplates } from '../marketing.js';
import { Empty, ErrorNote, Loading } from './Bits.jsx';
import { SelectField, TextArea, TextField } from './Fields.jsx';
import Modal from './Modal.jsx';

const NAME_LIMIT = 100;
const NOTES_LIMIT = 2000;

const sameName = (a, b) => a.trim().toLowerCase() === b.trim().toLowerCase();

/** What a template saves from a post's form, or from the template form. */
function templatePayload(name, form) {
  const text = (value) => (value && value.trim() ? value : null);
  return {
    name: name.trim(),
    title: text(form.title),
    body: text(form.body),
    notes: text(form.notes),
    theme: form.theme,
    account: form.account,
  };
}

/**
 * The Templates view on Marketing: the team's post templates, each with Use (a new post from it),
 * Edit and Delete (after a confirmation).
 */
export default function PostTemplates({ onUse }) {
  const toast = useToast();
  const templates = usePostTemplates();
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const list = templates.data;

  if (!list) {
    return templates.error ? <ErrorNote>{errorMessage(templates.error)}</ErrorNote> : <Loading label="Loading templates" />;
  }

  return (
    <>
      <section className="panel" aria-labelledby="post-templates">
        <div className="panel-head">
          <h2 id="post-templates">
            Templates <span className="panel-meta">Pick one when you add or write a post. Shared with the team.</span>
          </h2>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditing({})}>
            New template
          </button>
        </div>
        {list.length === 0 ? (
          <Empty title="No templates yet">Add one here, or open a post and choose Save as template.</Empty>
        ) : (
          <ul className="template-list">
            {list.map((template) => (
              <li key={template.id} className="template-item">
                <div className="template-text">
                  <p className="template-name">
                    {template.name}{' '}
                    <span className="muted">
                      {[template.theme !== 'OTHER' && POST_THEME[template.theme], POST_ACCOUNT[template.account]]
                        .filter(Boolean)
                        .join(', ')}
                    </span>
                  </p>
                  {template.title && <p className="template-subject">{template.title}</p>}
                  {template.body ? (
                    <p className="template-body">{template.body}</p>
                  ) : (
                    template.notes && <p className="template-body">Notes: {template.notes}</p>
                  )}
                  {template.ownerName && <p className="field-hint">By {template.ownerName}</p>}
                </div>
                <div className="template-actions">
                  <button type="button" className="btn btn-sm" onClick={() => onUse(template)} aria-label={`Use ${template.name}`}>
                    Use
                  </button>
                  <button type="button" className="btn btn-sm" onClick={() => setEditing(template)} aria-label={`Edit ${template.name}`}>
                    Edit
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm btn-quiet"
                    onClick={() => setDeleting(template)}
                    aria-label={`Delete ${template.name}`}
                  >
                    Delete
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing && (
        <Modal title={editing.id ? `Edit ${editing.name}` : 'New template'} onClose={() => setEditing(null)} wide>
          <TemplateForm
            template={editing}
            templates={list}
            onCancel={() => setEditing(null)}
            onDelete={() => {
              setDeleting(editing);
              setEditing(null);
            }}
            onSaved={(saved) => {
              setEditing(null);
              templates.reload();
              toast(editing.id ? `Saved ${saved.name}` : `Added ${saved.name}`);
            }}
          />
        </Modal>
      )}

      {deleting && (
        <Modal title="Delete this template?" onClose={() => setDeleting(null)}>
          <DeleteTemplate
            template={deleting}
            onCancel={() => setDeleting(null)}
            onDeleted={() => {
              setDeleting(null);
              templates.reload();
              toast(`Deleted ${deleting.name}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}

/** Adds or changes a template. */
function TemplateForm({ template, templates, onCancel, onDelete, onSaved }) {
  const [name, setName] = useState(template.name ?? '');
  const [form, setForm] = useState(() => ({
    title: template.title ?? '',
    body: template.body ?? '',
    notes: template.notes ?? '',
    theme: template.theme ?? 'OTHER',
    account: template.account ?? 'PERSONAL',
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));
  const tooLong = form.body.length > POST_LIMIT;

  async function submit(event) {
    event.preventDefault();
    if (templates.some((other) => other.id !== template.id && sameName(other.name, name))) {
      setError(`There's already a template called ${name.trim()}. Pick another name.`);
      return;
    }
    if (![form.title, form.body, form.notes].some((value) => value.trim())) {
      setError('Add a title, notes or the post’s text to the template.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const payload = templatePayload(name, form);
      onSaved(template.id ? await api.marketing.templates.update(template.id, payload) : await api.marketing.templates.create(payload));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form post-form" onSubmit={submit}>
      <TextField
        label="Template name"
        value={name}
        onChange={setName}
        required
        maxLength={NAME_LIMIT}
        autoFocus={!template.id}
        hint="What it’s called when you pick it, like Weekly hiring note."
      />
      <TextField
        label="Title (optional)"
        value={form.title}
        onChange={set('title')}
        maxLength={TITLE_LIMIT}
        hint="Fills in a post’s title when it doesn’t have one yet."
      />
      <div className="form-row">
        <SelectField label="Theme" value={form.theme} onChange={set('theme')} options={options(POST_THEME)} required />
        <fieldset className="field post-account">
          <legend>Goes on</legend>
          <span className="segmented segmented-small">
            {Object.entries(POST_ACCOUNT).map(([value, label]) => (
              <label key={value} className={form.account === value ? 'segment segment-on' : 'segment'}>
                <input type="radio" name="template-account" checked={form.account === value} onChange={() => set('account')(value)} />
                {label}
              </label>
            ))}
          </span>
        </fieldset>
      </div>
      <TextArea
        label="Notes (optional)"
        rows={3}
        value={form.notes}
        onChange={set('notes')}
        maxLength={NOTES_LIMIT}
        hint="Points every post like this should make. AI drafts use them."
      />
      <div className="field">
        <label htmlFor="template-body">Post (optional)</label>
        <textarea id="template-body" rows={10} value={form.body} onChange={(event) => set('body')(event.target.value)} />
        <p className={`field-hint ${tooLong ? 'composer-warn' : ''}`}>
          {form.body.length.toLocaleString()} of {POST_LIMIT.toLocaleString()} characters. Leave blanks like [number] to fill
          in for each post.
        </p>
      </div>

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        {template.id && (
          <span className="form-actions-left">
            <button type="button" className="btn btn-quiet" onClick={onDelete}>
              Delete
            </button>
          </span>
        )}
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || tooLong}>
          {busy ? 'Saving…' : template.id ? 'Save template' : 'Add template'}
        </button>
      </div>
    </form>
  );
}

/** The confirmation before a template is deleted. */
function DeleteTemplate({ template, onCancel, onDeleted }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function confirm() {
    setBusy(true);
    setError('');
    try {
      await api.marketing.templates.remove(template.id);
      onDeleted();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="form">
      <p>
        <strong>{template.name}</strong> will be deleted for everyone on the team. Posts already made from it keep their
        text. This can’t be undone.
      </p>
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel} autoFocus>
          Cancel
        </button>
        <button type="button" className="btn btn-danger-solid" disabled={busy} onClick={confirm}>
          {busy ? 'Deleting…' : 'Delete template'}
        </button>
      </div>
    </div>
  );
}

/**
 * In the post editor: saves what's in the post as a template. A name that's already taken updates
 * that template instead, after saying so.
 */
export function SaveAsTemplate({ form, templates, defaultName, onCancel, onSaved }) {
  const [name, setName] = useState(defaultName ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const existing = name.trim() ? templates.find((template) => sameName(template.name, name)) : null;

  async function save() {
    if (!name.trim()) {
      setError('Give the template a name.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const payload = templatePayload(existing ? existing.name : name, form);
      const saved = existing
        ? await api.marketing.templates.update(existing.id, payload)
        : await api.marketing.templates.create(payload);
      onSaved(saved, Boolean(existing));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="post-template-save">
      <div className="post-step">
        <TextField
          label="Template name"
          value={name}
          onChange={setName}
          maxLength={NAME_LIMIT}
          autoFocus
          onKeyDown={(event) => {
            // Enter saves the template, not the post around it.
            if (event.key === 'Enter') {
              event.preventDefault();
              save();
            }
          }}
        />
        <button type="button" className="btn btn-primary" disabled={busy} onClick={save}>
          {busy ? 'Saving…' : existing ? 'Update template' : 'Save template'}
        </button>
        <button type="button" className="btn btn-quiet" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
      <p className="field-hint">
        {existing
          ? `This replaces the saved ${existing.name} with this post’s title, text, notes, theme and where it goes.`
          : 'Saves this post’s title, text, notes, theme and where it goes, for the team to start from.'}
      </p>
      <ErrorNote>{error}</ErrorNote>
    </div>
  );
}
