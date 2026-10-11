import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { api, errorMessage } from '../api.js';
import { ErrorNote, LoginBrand } from '../components/Bits.jsx';
import { TextField } from '../components/Fields.jsx';
import { useAuth, useDesktop, useTitle } from '../hooks.js';

export default function LoginPage() {
  const { user, ready, login } = useAuth();
  const desktop = useDesktop();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [resetting, setResetting] = useState(false);
  const [notice, setNotice] = useState('');
  useTitle(resetting ? 'Reset your password' : 'Sign in');

  const destination = location.state?.from || '/';

  if (ready && user) {
    return <Navigate to={destination} replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await login(email.trim(), password);
      navigate(destination, { replace: true });
    } catch (err) {
      setError(errorMessage(err, 'Sign-in failed. Try again.'));
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <div className="login-panel">
        <LoginBrand title={resetting ? 'Reset your password' : 'Recruiting CRM'} />

        {resetting ? (
          <ResetWithKey
            email={email}
            onDone={(resetEmail) => {
              setEmail(resetEmail);
              setPassword('');
              setError('');
              setNotice('Password changed. Sign in with the new one.');
              setResetting(false);
            }}
            onCancel={() => setResetting(false)}
          />
        ) : (
          <>
            <form className="form" onSubmit={submit}>
              {notice && <p className="setup-note">{notice}</p>}
              <TextField label="Email" type="email" value={email} onChange={setEmail} autoComplete="username" required autoFocus />
              <TextField
                label="Password"
                type="password"
                value={password}
                onChange={setPassword}
                autoComplete="current-password"
                required
              />
              <ErrorNote>{error}</ErrorNote>
              <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
            <p className="login-help">
              Accounts are created by an admin on the Team page.
              {desktop && (
                <>
                  {' '}
                  <button type="button" className="link-button" onClick={() => setResetting(true)}>
                    Forgot your password?
                  </button>
                </>
              )}
            </p>
          </>
        )}
      </div>
    </div>
  );
}

/** Desktop only: the CRM key from the password manager sets a new password. */
function ResetWithKey({ email: initialEmail, onDone, onCancel }) {
  const [form, setForm] = useState({ email: initialEmail, key: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (field) => (value) => setForm((current) => ({ ...current, [field]: value }));

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const email = form.email.trim();
      await api.setup.resetPassword({ email, key: form.key.trim(), password: form.password });
      onDone(email);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <>
      <form className="form" onSubmit={submit}>
        <p className="form-intro">An admin can also set a new password for you on the Team page.</p>
        <TextField label="Email" type="email" value={form.email} onChange={set('email')} autoComplete="username" required />
        <TextField
          label="CRM key"
          value={form.key}
          onChange={set('key')}
          autoComplete="off"
          spellCheck={false}
          hint="Saved in the password manager when the CRM was started."
          required
          autoFocus
        />
        <TextField
          label="New password"
          type="password"
          value={form.password}
          onChange={set('password')}
          autoComplete="new-password"
          minLength={12}
          hint="At least 12 characters."
          required
        />
        <ErrorNote>{error}</ErrorNote>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Saving…' : 'Set new password'}
        </button>
      </form>
      <p className="login-help">
        <button type="button" className="link-button" onClick={onCancel}>
          Back to sign in
        </button>
      </p>
    </>
  );
}
