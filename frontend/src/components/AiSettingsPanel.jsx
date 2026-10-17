import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { useLoader, useToast } from '../hooks.js';
import { ConfirmButton, ErrorNote, ExternalLink } from './Bits.jsx';
import { SelectField, TextField } from './Fields.jsx';

const OPENAI_KEY_HELP = 'https://platform.openai.com/api-keys';

function modelLabel(provider) {
  return provider.models.find((model) => model.id === provider.model)?.label ?? provider.model;
}

/**
 * Which AI writes post drafts and idea suggestions, and the OpenAI key. The account owner's to
 * change; everyone else sees what's in use. The Claude key and the limits are on the Spending panel.
 */
export default function AiSettingsPanel() {
  const status = useLoader(() => api.ai.status(), [], 'ai:status');
  if (!status.data) {
    return status.error ? <ErrorNote>{errorMessage(status.error)}</ErrorNote> : null;
  }
  return <AiSettings data={status.data} onSaved={status.setData} />;
}

function AiSettings({ data, onSaved }) {
  const toast = useToast();
  const current = data.providers.find((provider) => provider.id === data.provider);
  const openAi = data.providers.find((provider) => provider.id === 'OPENAI');
  const [choice, setChoice] = useState(data.provider);
  const [models, setModels] = useState(() => Object.fromEntries(data.providers.map((provider) => [provider.id, provider.model])));
  const [editingKey, setEditingKey] = useState(false);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState('');
  const chosen = data.providers.find((provider) => provider.id === choice);

  async function act(name, call, done) {
    setBusy(name);
    setError('');
    try {
      onSaved(await call());
      toast(done);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(null);
    }
  }

  if (!data.canManage) {
    return (
      <section className="panel" aria-labelledby="ai-writing">
        <div className="panel-head">
          <h2 id="ai-writing">Writing with AI</h2>
        </div>
        <p className="panel-meta">
          Post drafts and idea suggestions are written by {current.label} ({modelLabel(current)}). Only{' '}
          {data.owner ?? 'the owner'} can change this.
        </p>
      </section>
    );
  }

  function saveChoice(event) {
    event.preventDefault();
    act('choice', () => api.ai.choose({ provider: choice, model: models[choice] }), `${chosen.label} writes drafts now`);
  }

  async function saveKey(event) {
    event.preventDefault();
    if (await act('key', () => api.ai.saveOpenAiKey(key), 'OpenAI key saved')) {
      setKey('');
      setEditingKey(false);
    }
  }

  const unchanged = choice === data.provider && models[choice] === current.model;

  return (
    <section className="panel" aria-labelledby="ai-writing">
      <div className="panel-head">
        <h2 id="ai-writing">Writing with AI</h2>
        <span className="panel-meta">For post drafts and idea suggestions on Marketing. The finder and message drafts use Claude.</span>
      </div>
      <ErrorNote>{error}</ErrorNote>

      <form className="form" onSubmit={saveChoice}>
        <fieldset className="segmented segmented-small ai-provider-pick">
          <legend className="visually-hidden">Writes drafts</legend>
          {data.providers.map((provider) => (
            <label key={provider.id} className={choice === provider.id ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="ai-provider" checked={choice === provider.id} onChange={() => setChoice(provider.id)} />
              {provider.label}
            </label>
          ))}
        </fieldset>
        <div className="form-row">
          <SelectField
            label="Model"
            value={models[choice]}
            onChange={(value) => setModels((all) => ({ ...all, [choice]: value }))}
            options={chosen.models.map((model) => ({ value: model.id, label: model.label }))}
            required
          />
        </div>
        {!chosen.hasKey && (
          <p className="field-hint composer-warn">
            {chosen.label} has no API key yet, so drafting won’t work until one is added
            {chosen.id === 'CLAUDE' ? ' on the Spending panel.' : ' below.'}
          </p>
        )}
        <div className="form-actions form-actions-start">
          <button type="submit" className="btn btn-primary" disabled={busy === 'choice' || unchanged}>
            {busy === 'choice' ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>

      {openAi.hasKey && !editingKey ? (
        <div className="setting-row finder-form">
          <p>
            <strong>OpenAI API key</strong>
            Ends in {openAi.keyHint ?? '••••'}. Stored encrypted and shared with the team’s computers. Drafts count against the same
            limits as everything else.
          </p>
          <span className="setting-actions">
            <button type="button" className="btn btn-sm" onClick={() => setEditingKey(true)}>
              Replace
            </button>
            <ConfirmButton
              label="Remove"
              confirmLabel="Remove the key"
              className="btn btn-sm btn-quiet"
              onConfirm={() => act('key', () => api.ai.removeOpenAiKey(), 'OpenAI key removed')}
            />
          </span>
        </div>
      ) : (
        <form className="form finder-form" onSubmit={saveKey}>
          <p className="form-intro">
            To write with OpenAI, create a key on the OpenAI platform (<ExternalLink href={OPENAI_KEY_HELP}>API keys</ExternalLink>) and paste
            it here. Usage is billed to that account and counts against the limits above. The CRM checks the key before saving it.
          </p>
          <div className="inline-form">
            <TextField
              label="OpenAI API key"
              type="password"
              autoComplete="off"
              spellCheck={false}
              value={key}
              onChange={setKey}
              placeholder="sk-…"
              required
            />
            <button type="submit" className="btn btn-primary" disabled={busy === 'key' || !key.trim()}>
              {busy === 'key' ? 'Checking…' : 'Save key'}
            </button>
            {openAi.hasKey && (
              <button type="button" className="btn btn-quiet" onClick={() => setEditingKey(false)}>
                Cancel
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
