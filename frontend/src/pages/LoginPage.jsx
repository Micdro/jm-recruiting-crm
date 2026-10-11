import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { errorMessage } from '../api.js';
import { ErrorNote } from '../components/Bits.jsx';
import { TextField } from '../components/Fields.jsx';
import { useAuth, useTitle } from '../hooks.js';

export default function LoginPage() {
  useTitle('Sign in');
  const { user, ready, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

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
            <h1>Recruiting CRM</h1>
          </div>
        </div>

        <form className="form" onSubmit={submit}>
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
        <p className="login-help">Accounts are created by an admin on the Team page.</p>
      </div>
    </div>
  );
}
