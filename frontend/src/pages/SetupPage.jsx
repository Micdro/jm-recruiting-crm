import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote, LoginBrand } from '../components/Bits.jsx';
import { CheckboxField, TextField } from '../components/Fields.jsx';
import FolderField from '../components/FolderField.jsx';
import { useTitle } from '../hooks.js';

/**
 * First run of the desktop app. The first computer starts a new CRM; every other computer joins it
 * and copies everything over.
 */
export default function SetupPage({ status, onDone }) {
  useTitle('Set up');
  const [mode, setMode] = useState('join');
  const [result, setResult] = useState(null);

  return (
    <div className="login">
      <div className="login-panel setup-panel">
        <LoginBrand title="Set up this computer" />

        {result?.key && <SaveKey value={result.key} onDone={onDone} />}
        {result?.joined && <Joined result={result.joined} onDone={onDone} />}

        {!result && (
          <>
            <fieldset className="segmented setup-choice">
              <legend className="visually-hidden">How to set up</legend>
              {[
                ['join', 'Join the team’s CRM'],
                ['start', 'Start a new CRM'],
              ].map(([value, label]) => (
                <label key={value} className={mode === value ? 'segment segment-on' : 'segment'}>
                  <input type="radio" name="setup-mode" checked={mode === value} onChange={() => setMode(value)} />
                  {label}
                </label>
              ))}
            </fieldset>

            {mode === 'join' ? (
              <JoinForm status={status} onJoined={(joined) => setResult({ joined })} />
            ) : (
              <StartForm status={status} onStarted={(key) => setResult({ key })} />
            )}
          </>
        )}
      </div>
    </div>
  );
}

function JoinForm({ status, onJoined }) {
  const [syncFolder, setSyncFolder] = useState('');
  const [key, setKey] = useState('');
  const [computerName, setComputerName] = useState(status.suggestedName ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onJoined(await api.setup.join({ syncFolder, key: key.trim(), computerName }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <p className="form-intro">
        For every computer after the first. This computer gets its own copy of the CRM and stays in step with the others
        through the shared Google Drive folder.
      </p>
      <FolderField
        value={syncFolder}
        onChange={setSyncFolder}
        joining
        label="Shared sync folder"
        hint="The JM CRM Sync folder shared with you in Google Drive. Add it to your My Drive first so it appears here."
        required
        autoFocus
      />
      <TextField
        label="CRM key"
        value={key}
        onChange={setKey}
        autoComplete="off"
        spellCheck={false}
        hint="Saved in the password manager when the CRM was started."
        required
      />
      <TextField label="Name for this computer" value={computerName} onChange={setComputerName} maxLength={100} />
      <ErrorNote>{error}</ErrorNote>
      <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
        {busy ? 'Copying the CRM to this computer…' : 'Join and copy the CRM'}
      </button>
    </form>
  );
}

function StartForm({ status, onStarted }) {
  const [form, setForm] = useState({
    computerName: status.suggestedName ?? '',
    name: '',
    email: '',
    password: '',
    syncFolder: '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await api.setup.start(form);
      onStarted(result.key);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <p className="form-intro">
        Only for the first computer. Every other computer joins this one afterwards.
      </p>
      <TextField label="Your name" value={form.name} onChange={set('name')} autoComplete="name" required autoFocus maxLength={100} />
      <TextField label="Email" type="email" value={form.email} onChange={set('email')} autoComplete="username" required />
      <TextField
        label="Password"
        type="password"
        value={form.password}
        onChange={set('password')}
        autoComplete="new-password"
        minLength={12}
        hint="At least 12 characters."
        required
      />
      <FolderField
        value={form.syncFolder}
        onChange={set('syncFolder')}
        hint="A new folder in Google Drive that you’ll share with the team. You can also choose it later on the This computer page."
      />
      <TextField label="Name for this computer" value={form.computerName} onChange={set('computerName')} maxLength={100} />
      <ErrorNote>{error}</ErrorNote>
      <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
        {busy ? 'Starting…' : 'Start the CRM'}
      </button>
    </form>
  );
}

function SaveKey({ value, onDone }) {
  const [copied, setCopied] = useState(false);
  const [saved, setSaved] = useState(false);

  return (
    <div className="form">
      <h2>Save your CRM key</h2>
      <p>
        Put this key in your password manager now. You’ll need it to add or replace a computer, and it’s the only way into the
        data if every password is forgotten. It isn’t saved on this computer and won’t be shown again.
      </p>
      <div className="key-box">
        <code>{value}</code>
        <button
          type="button"
          className="btn btn-sm"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
            } catch {
              setCopied(false);
            }
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <CheckboxField label="I saved the key in my password manager" checked={saved} onChange={setSaved} />
      <button type="button" className="btn btn-primary btn-block" disabled={!saved} onClick={onDone}>
        Continue to sign in
      </button>
    </div>
  );
}

function Joined({ result, onDone }) {
  const copied = result.copied === 1 ? '1 record' : `${result.copied.toLocaleString()} records`;
  return (
    <div className="form">
      <h2>This computer is connected</h2>
      <p>
        {result.computers > 0 ? `Copied ${copied} from the shared folder. ` : ''}
        From now on, changes on any computer reach the others through the folder, usually within a minute. Sign in with your
        usual email and password.
      </p>
      {result.warning && <p className="warn-note">{result.warning}</p>}
      <button type="button" className="btn btn-primary btn-block" onClick={onDone}>
        Continue to sign in
      </button>
    </div>
  );
}
