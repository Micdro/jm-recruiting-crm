import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import { CheckboxField, SelectField, TextField } from '../components/Fields.jsx';
import Modal from '../components/Modal.jsx';
import { ROLE, options } from '../labels.js';
import { refreshUsers, useAuth, useLoader, useTitle, useToast } from '../hooks.js';

export default function TeamPage() {
  useTitle('Team');
  const { user } = useAuth();
  const toast = useToast();
  const users = useLoader(() => api.users.list(), [], 'users');
  const [modal, setModal] = useState(null);

  const isAdmin = user?.role === 'ADMIN';

  function changed(message) {
    setModal(null);
    refreshUsers();
    users.reload();
    toast(message);
  }

  return (
    <>
      <PageHeader title="Team" sub="Who can sign in to the CRM.">
        {isAdmin && (
          <button type="button" className="btn btn-primary" onClick={() => setModal({ kind: 'add' })}>
            Add teammate
          </button>
        )}
      </PageHeader>

      {users.error && <ErrorNote>{errorMessage(users.error)}</ErrorNote>}
      {!users.data && users.loading && <Loading label="Loading the team" />}

      {users.data && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">Team</caption>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">Role</th>
                <th scope="col">Access</th>
                {isAdmin && (
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {users.data.map((member) => (
                <tr key={member.id}>
                  <td className="cell-title">
                    {member.displayName}
                    {member.id === user?.id && <span className="cell-sub">You</span>}
                  </td>
                  <td>{member.email}</td>
                  <td>{ROLE[member.role]}</td>
                  <td>{member.active ? 'Active' : <span className="muted">Deactivated</span>}</td>
                  {isAdmin && (
                    <td className="cell-actions">
                      <button type="button" className="btn btn-sm" onClick={() => setModal({ kind: 'edit', member })}>
                        Edit
                      </button>
                      <button type="button" className="btn btn-sm" onClick={() => setModal({ kind: 'reset', member })}>
                        Reset password
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <section className="panel narrow">
        <div className="panel-head">
          <h2>Change your password</h2>
        </div>
        <ChangePasswordForm onDone={() => toast('Password changed')} />
      </section>

      {modal?.kind === 'add' && (
        <Modal title="Add teammate" onClose={() => setModal(null)}>
          <AddUserForm onCancel={() => setModal(null)} onSaved={(saved) => changed(`Added ${saved.displayName}`)} />
        </Modal>
      )}
      {modal?.kind === 'edit' && (
        <Modal title={`Edit ${modal.member.displayName}`} onClose={() => setModal(null)}>
          <EditUserForm member={modal.member} onCancel={() => setModal(null)} onSaved={() => changed('Saved')} />
        </Modal>
      )}
      {modal?.kind === 'reset' && (
        <Modal title={`Reset password for ${modal.member.displayName}`} onClose={() => setModal(null)}>
          <ResetPasswordForm member={modal.member} onCancel={() => setModal(null)} onSaved={() => changed('Password reset')} />
        </Modal>
      )}
    </>
  );
}

function useSubmit(action, onSuccess) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await action();
      setBusy(false);
      onSuccess(result);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };
  return { busy, error, submit };
}

function AddUserForm({ onCancel, onSaved }) {
  const [form, setForm] = useState({ displayName: '', email: '', role: 'MEMBER', password: '' });
  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));
  const { busy, error, submit } = useSubmit(() => api.users.create(form), onSaved);

  return (
    <form className="form" onSubmit={submit}>
      <TextField label="Name" value={form.displayName} onChange={set('displayName')} required maxLength={100} autoFocus />
      <TextField label="Email" type="email" value={form.email} onChange={set('email')} required maxLength={255} />
      <SelectField label="Role" value={form.role} onChange={set('role')} options={options(ROLE)} required hint="Admins can add teammates and reset passwords." />
      <TextField
        label="Temporary password"
        type="text"
        value={form.password}
        onChange={set('password')}
        required
        minLength={10}
        autoComplete="off"
        hint="At least 10 characters. Share it privately and ask them to change it after signing in."
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Add teammate
        </button>
      </div>
    </form>
  );
}

function EditUserForm({ member, onCancel, onSaved }) {
  const [form, setForm] = useState({ displayName: member.displayName, role: member.role, active: member.active });
  const set = (key) => (value) => setForm((current) => ({ ...current, [key]: value }));
  const { busy, error, submit } = useSubmit(() => api.users.update(member.id, form), onSaved);

  return (
    <form className="form" onSubmit={submit}>
      <TextField label="Name" value={form.displayName} onChange={set('displayName')} required maxLength={100} />
      <SelectField label="Role" value={form.role} onChange={set('role')} options={options(ROLE)} required />
      <CheckboxField
        label="Can sign in"
        checked={form.active}
        onChange={set('active')}
        hint="Deactivating keeps their history but blocks sign-in."
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Save
        </button>
      </div>
    </form>
  );
}

function ResetPasswordForm({ member, onCancel, onSaved }) {
  const [password, setPassword] = useState('');
  const { busy, error, submit } = useSubmit(() => api.users.resetPassword(member.id, password), onSaved);

  return (
    <form className="form" onSubmit={submit}>
      <TextField
        label="New password"
        type="text"
        value={password}
        onChange={setPassword}
        required
        minLength={10}
        autoComplete="off"
        hint={`At least 10 characters. Share it with ${member.displayName} privately.`}
        autoFocus
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Reset password
        </button>
      </div>
    </form>
  );
}

function ChangePasswordForm({ onDone }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const { busy, error, submit } = useSubmit(
    () => api.users.changeOwnPassword(current, next),
    () => {
      setCurrent('');
      setNext('');
      onDone();
    },
  );

  return (
    <form className="form" onSubmit={submit}>
      <TextField
        label="Current password"
        type="password"
        value={current}
        onChange={setCurrent}
        required
        autoComplete="current-password"
      />
      <TextField
        label="New password"
        type="password"
        value={next}
        onChange={setNext}
        required
        minLength={10}
        autoComplete="new-password"
        hint="At least 10 characters."
      />
      <ErrorNote>{error}</ErrorNote>
      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Change password
        </button>
      </div>
    </form>
  );
}
