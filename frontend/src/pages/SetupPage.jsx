import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { CheckboxField, TextField } from '../components/Fields.jsx';
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
        <div className="login-brand">
          <svg viewBox="0 0 32 32" width="40" height="40" aria-hidden="true">
            <rect width="32" height="32" rx="6" fill="currentColor" />
            <g stroke="#6FC3A6" strokeWidth="2.4" strokeLinecap="round">
              <path d="M9 9v14M13.5 9v14M18 9v14M22.5 9v14" />
              <path d="M6.5 20.5 25 11.5" stroke="#F4F6F5" />
            </g>
          </svg>
          <div>
            <p className="login-firm">Jane Michael</p>
            <h1>Set up this computer</h1>
          </div>
        </div>

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
  const [address, setAddress] = useState('');
  const [key, setKey] = useState('');
  const [computerName, setComputerName] = useState(status.suggestedName ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onJoined(await api.setup.join({ address, key, computerName }));
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <p className="form-intro">
        For every computer after the first. This computer gets its own copy of the CRM and keeps it in step with the others.
      </p>
      <TextField
        label="The other computer"
        value={address}
        onChange={setAddress}
        placeholder="michael-pc"
        hint="Its name in Tailscale, or its Tailscale address (100.x.x.x). The CRM must be open there."
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
      <p className="setup-note">Windows will ask whether JM CRM may accept connections from your other computers. Click Yes.</p>
      <ErrorNote>{error}</ErrorNote>
      <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
        {busy ? 'Copying the CRM to this computer…' : 'Join and copy the CRM'}
      </button>
    </form>
  );
}

function StartForm({ status, onStarted }) {
  const [form, setForm] = useState({ computerName: status.suggestedName ?? '', name: '', email: '', password: '' });
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
        minLength={10}
        hint="At least 10 characters."
        required
      />
      <TextField label="Name for this computer" value={form.computerName} onChange={set('computerName')} maxLength={100} />
      <p className="setup-note">Windows will ask whether JM CRM may accept connections from your other computers. Click Yes.</p>
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
        Put this key in your password manager now. You’ll need it to add the other computers, and it’s the only way to read
        the data if this computer is ever replaced. It won’t be shown again.
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
  return (
    <div className="form">
      <h2>This computer is connected</h2>
      <p>
        Everything was copied from {result.joinedThrough}, and changes on either computer now reach the other within a few seconds
        while both are on. Sign in with your usual email and password.
      </p>
      {result.warning && <p className="warn-note">{result.warning}</p>}
      <button type="button" className="btn btn-primary btn-block" onClick={onDone}>
        Continue to sign in
      </button>
    </div>
  );
}
