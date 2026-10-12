import { useRef, useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ConfirmButton, Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import { TextField } from '../components/Fields.jsx';
import Modal from '../components/Modal.jsx';
import { useAuth, useLoader, useTitle, useToast } from '../hooks.js';
import { MAX_TOUCHES } from '../labels.js';
import { LINKEDIN_NOTE_LIMIT, PLACEHOLDERS, STARTER_TEMPLATES, channelForTouch, fill } from '../templates.js';

const CHANNEL = { LINKEDIN: 'LinkedIn', EMAIL: 'Email' };

/** Who the preview pretends to write to. */
const SAMPLE = { name: 'Dana Reyes', title: 'VP of Engineering', companyName: 'Ledgerlight AI' };

const GROUPS = [
  ...Array.from({ length: MAX_TOUCHES }, (_, index) => ({
    touch: index + 1,
    title: `Touch ${index + 1}`,
    sub: `${CHANNEL[channelForTouch(index + 1)]} in the sequence`,
  })),
  { touch: null, title: 'Any touch', sub: 'Replies, follow-ups and anything else' },
];

export default function TemplatesPage() {
  useTitle('Templates');
  const toast = useToast();
  const templates = useLoader(() => api.templates.list(), [], 'templates');
  const [editing, setEditing] = useState(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState('');

  async function addStarters() {
    setError('');
    setAdding(true);
    try {
      for (const template of STARTER_TEMPLATES) {
        await api.templates.create(template);
      }
      templates.reload();
      toast('Added four starter templates. Edit them to sound like you.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setAdding(false);
    }
  }

  const list = templates.data;

  return (
    <>
      <PageHeader title="Templates" sub="Saved messages for each touch. The details in braces fill in for each contact.">
        <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
          Add template
        </button>
      </PageHeader>
      <ErrorNote>{error || (templates.error && errorMessage(templates.error))}</ErrorNote>
      {!list && <Loading label="Loading templates" />}

      {list && list.length === 0 && (
        <section className="panel">
          <Empty title="No templates yet">
            Start with four starter messages, one for each touch, and make them your own.
          </Empty>
          <div className="form-actions form-actions-start">
            <button type="button" className="btn btn-primary" disabled={adding} onClick={addStarters}>
              {adding ? 'Adding…' : 'Add starter templates'}
            </button>
          </div>
        </section>
      )}

      {list &&
        list.length > 0 &&
        GROUPS.map((group) => {
          const inGroup = list.filter((template) => (template.touch ?? null) === group.touch);
          if (inGroup.length === 0 && group.touch === null) return null;
          return (
            <section key={group.title} className="panel" aria-labelledby={`group-${group.touch ?? 'any'}`}>
              <div className="panel-head">
                <h2 id={`group-${group.touch ?? 'any'}`}>
                  {group.title} <span className="panel-meta">{group.sub}</span>
                </h2>
              </div>
              {inGroup.length === 0 ? (
                <p className="muted">No template for this touch yet.</p>
              ) : (
                <ul className="template-list">
                  {inGroup.map((template) => (
                    <li key={template.id} className="template-item">
                      <div className="template-text">
                        <p className="template-name">
                          {template.name} <span className="muted">{CHANNEL[template.channel]}</span>
                        </p>
                        {template.subject && <p className="template-subject">{template.subject}</p>}
                        <p className="template-body">{template.body}</p>
                        {template.ownerName && <p className="field-hint">By {template.ownerName}</p>}
                      </div>
                      <div className="template-actions">
                        <button type="button" className="btn btn-sm" onClick={() => setEditing(template)}>
                          Edit
                        </button>
                        <ConfirmButton
                          className="btn btn-sm btn-quiet"
                          onConfirm={async () => {
                            try {
                              await api.templates.remove(template.id);
                              templates.reload();
                              toast(`Deleted ${template.name}`);
                            } catch (err) {
                              setError(errorMessage(err));
                            }
                          }}
                        />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          );
        })}

      {editing && (
        <Modal title={editing.id ? `Edit ${editing.name}` : 'Add template'} onClose={() => setEditing(null)} wide>
          <TemplateForm
            template={editing}
            onCancel={() => setEditing(null)}
            onSaved={(saved) => {
              setEditing(null);
              templates.reload();
              toast(editing.id ? 'Saved' : `Added ${saved.name}`);
            }}
          />
        </Modal>
      )}
    </>
  );
}

function TemplateForm({ template, onCancel, onSaved }) {
  const { user } = useAuth();
  const body = useRef(null);
  const [form, setForm] = useState({
    name: template.name ?? '',
    channel: template.channel ?? 'EMAIL',
    touch: template.touch ? String(template.touch) : '',
    subject: template.subject ?? '',
    body: template.body ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));

  /** Puts a placeholder where the cursor is in the message. */
  function insert(key) {
    const textarea = body.current;
    const token = `{${key}}`;
    const start = textarea?.selectionStart ?? form.body.length;
    const end = textarea?.selectionEnd ?? form.body.length;
    const next = form.body.slice(0, start) + token + form.body.slice(end);
    set('body')(next);
    requestAnimationFrame(() => {
      textarea?.focus();
      textarea?.setSelectionRange(start + token.length, start + token.length);
    });
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const payload = {
      name: form.name,
      channel: form.channel,
      touch: form.touch ? Number(form.touch) : null,
      subject: form.channel === 'EMAIL' ? form.subject : null,
      body: form.body,
    };
    try {
      onSaved(template.id ? await api.templates.update(template.id, payload) : await api.templates.create(payload));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  const note = form.channel === 'LINKEDIN' && form.touch === '1';
  const preview = fill(form.body, SAMPLE, user);

  return (
    <form className="form template-form" onSubmit={submit}>
      <div className="template-form-grid">
        <TextField label="Name" value={form.name} onChange={set('name')} required maxLength={100} autoFocus />
        <div className="field">
          <label htmlFor="template-touch">Touch</label>
          <select id="template-touch" value={form.touch} onChange={(event) => set('touch')(event.target.value)}>
            <option value="">Any touch</option>
            {Array.from({ length: MAX_TOUCHES }, (_, index) => (
              <option key={index} value={String(index + 1)}>
                Touch {index + 1} ({CHANNEL[channelForTouch(index + 1)]})
              </option>
            ))}
          </select>
        </div>
      </div>

      <fieldset className="segmented segmented-small">
        <legend className="visually-hidden">Sent by</legend>
        {Object.entries(CHANNEL).map(([value, label]) => (
          <label key={value} className={form.channel === value ? 'segment segment-on' : 'segment'}>
            <input type="radio" name="template-channel" checked={form.channel === value} onChange={() => set('channel')(value)} />
            {label}
          </label>
        ))}
      </fieldset>

      {form.channel === 'EMAIL' && (
        <TextField label="Subject" value={form.subject} onChange={set('subject')} maxLength={200} />
      )}

      <div className="field">
        <label htmlFor="template-body">Message</label>
        <textarea
          id="template-body"
          ref={body}
          rows={form.channel === 'EMAIL' ? 12 : 7}
          value={form.body}
          onChange={(event) => set('body')(event.target.value)}
          required
          maxLength={5000}
        />
        <div className="placeholder-chips">
          <span className="muted">Insert:</span>
          {PLACEHOLDERS.map((placeholder) => (
            <button key={placeholder.key} type="button" className="btn btn-sm" onClick={() => insert(placeholder.key)} title={placeholder.label}>
              {`{${placeholder.key}}`}
            </button>
          ))}
        </div>
      </div>

      {form.body && (
        <div className="template-preview">
          <p className="field-hint">
            As {SAMPLE.name} at {SAMPLE.companyName} would get it
            {note ? `: ${preview.length} of ${LINKEDIN_NOTE_LIMIT} characters for a connection note` : ''}
          </p>
          {form.channel === 'EMAIL' && form.subject && (
            <p className="template-subject">{fill(form.subject, SAMPLE, user)}</p>
          )}
          <p className={`template-body template-body-full ${note && preview.length > LINKEDIN_NOTE_LIMIT ? 'composer-warn' : ''}`}>
            {preview}
          </p>
        </div>
      )}

      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Saving…' : template.id ? 'Save' : 'Add template'}
        </button>
      </div>
    </form>
  );
}
