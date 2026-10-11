import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote, LoginBrand } from '../components/Bits.jsx';
import { TextField } from '../components/Fields.jsx';
import { useAuth, useTitle } from '../hooks.js';

/**
 * Shown after signing in on a computer where this account can't open the CRM yet (for example an
 * account set up before passwords unlocked the CRM). The CRM key from the password manager unlocks
 * it once; from then on, signing in does.
 */
export default function UnlockPage() {
  useTitle('Unlock');
  const { user, logout, unlocked } = useAuth();
  const [key, setKey] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.desktop.unlock(key.trim(), password);
      await unlocked();
    } catch (err) {
      setError(errorMessage(err, 'Couldn’t unlock the CRM. Try again.'));
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <div className="login-panel setup-panel">
        <LoginBrand title="Unlock the CRM" />
        <form className="form" onSubmit={submit}>
          <p className="form-intro">
            Your password can’t unlock the CRM on this computer yet. Paste the CRM key from your password manager once.
            After that, signing in unlocks it.
          </p>
          <TextField
            label="CRM key"
            value={key}
            onChange={setKey}
            autoComplete="off"
            spellCheck={false}
            hint="Saved in the password manager when the CRM was started."
            required
            autoFocus
          />
          <TextField
            label="Your password"
            type="password"
            value={password}
            onChange={setPassword}
            autoComplete="current-password"
            hint={`The one you just signed in with${user?.email ? ` as ${user.email}` : ''}.`}
            required
          />
          <ErrorNote>{error}</ErrorNote>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Unlocking…' : 'Unlock'}
          </button>
        </form>
        <p className="login-help">
          No key? Ask an admin to reset your password on the Team page, then sign in with the new one.{' '}
          <button type="button" className="link-button" onClick={logout}>
            Sign out
          </button>
        </p>
      </div>
    </div>
  );
}
