import { useState } from 'react';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, ExternalLink, Loading } from './Bits.jsx';
import { JOB_BOARD, ROLE_TRACK } from '../labels.js';
import { formatDay, formatTimestamp, plural } from '../format.js';
import { useLoader, useToast } from '../hooks.js';

const READABLE = ['GREENHOUSE', 'LEVER', 'ASHBY'];

/** A company's job board and its open forward deployed and AI platform roles, on the company page. */
export default function JobBoardPanel({ companyId, companyName }) {
  const board = useLoader(() => api.signals.company(companyId), [companyId], `company:${companyId}:board`);
  const [editing, setEditing] = useState(false);
  const [link, setLink] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const toast = useToast();

  const data = board.data;
  const readable = data && READABLE.includes(data.provider);
  const showForm = data && (editing || !data.provider);

  async function run(action, message) {
    setBusy(true);
    setError('');
    try {
      const updated = await action();
      board.setData(updated);
      setEditing(false);
      setLink('');
      if (message) toast(typeof message === 'function' ? message(updated) : message);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="company-board" id="job-board">
      <div className="panel-head">
        <h2 id="company-board">Open roles</h2>
        {readable && !editing && (
          <span className="panel-actions">
            <button
              type="button"
              className="btn btn-sm"
              disabled={busy}
              onClick={() => run(() => api.signals.checkCompany(companyId), 'Board read')}
            >
              {busy ? 'Reading…' : 'Check now'}
            </button>
            <button type="button" className="btn btn-sm btn-quiet" onClick={() => setEditing(true)}>
              Change board
            </button>
          </span>
        )}
      </div>

      {!data && board.loading && <Loading label="Loading job board" />}
      {board.error && <ErrorNote>{errorMessage(board.error)}</ErrorNote>}
      <ErrorNote>{error}</ErrorNote>

      {data && readable && (
        <>
          <p className="panel-meta">
            {JOB_BOARD[data.provider]} board <ExternalLink href={data.boardUrl}>{data.token}</ExternalLink>.{' '}
            {data.checkedAt ? `Read ${formatTimestamp(data.checkedAt).toLowerCase()}` : 'Not read yet'}
            {data.openRoles !== null && data.openRoles !== undefined ? `, ${plural(data.openRoles, 'open role')} in all.` : '.'}
          </p>
          {!data.confirmed && (
            <p className="signal-confirm">
              This board was found automatically. Is it {companyName}’s?{' '}
              <button
                type="button"
                className="link-button"
                disabled={busy}
                onClick={() => run(() => api.signals.confirm(companyId), 'Board confirmed')}
              >
                Yes, it’s theirs
              </button>{' '}
              <button type="button" className="link-button" disabled={busy} onClick={() => setEditing(true)}>
                No, change it
              </button>
            </p>
          )}
          {data.error && <p className="signal-problem">{data.error}</p>}
          {data.roles.length === 0 ? (
            data.checkedAt && <Empty title="No forward deployed or AI platform roles open right now" />
          ) : (
            <ul className="signal-roles">
              {data.roles.map((role, index) => (
                <li key={`${index}-${role.url ?? role.title}`}>
                  <span className="signal-role">
                    {role.url ? <ExternalLink href={role.url}>{role.title}</ExternalLink> : role.title}
                    {role.unreviewed && <span className="pill pill-good">New</span>}
                  </span>
                  <span className="signal-role-meta">
                    {[ROLE_TRACK[role.track], role.location, role.publishedAt && `posted ${formatDay(role.publishedAt)}`]
                      .filter(Boolean)
                      .join(', ')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {data && data.provider === 'NONE' && !editing && (
        <p className="panel-meta">
          Marked as having no Greenhouse, Lever or Ashby board, so it isn’t looked up.{' '}
          <button type="button" className="link-button" onClick={() => setEditing(true)}>
            Add a board link
          </button>
        </p>
      )}

      {data && !data.provider && !editing && (
        <p className="panel-meta">
          No job board on file.
          {data.searchedAt ? ` Looked up ${formatDay(data.searchedAt)} and not found.` : ''} Add their board to see their
          open forward deployed and AI platform roles here and on Hiring signals.
        </p>
      )}

      {showForm && (
        <form
          className="form board-form"
          onSubmit={(event) => {
            event.preventDefault();
            run(() => api.signals.setBoard(companyId, { link }), (updated) =>
              `Saved. ${plural(updated.roles.length, 'matching role')} open.`,
            );
          }}
        >
          <div className="field">
            <label htmlFor="board-link">Job board link</label>
            <input
              id="board-link"
              type="text"
              inputMode="url"
              placeholder="job-boards.greenhouse.io/acme"
              value={link}
              onChange={(event) => setLink(event.target.value)}
            />
            <p className="field-hint">Their careers page on Greenhouse, Lever or Ashby. A link to one of their jobs works too.</p>
          </div>
          <div className="form-actions form-actions-start">
            <button type="submit" className="btn btn-primary btn-sm" disabled={busy || !link.trim()}>
              {busy ? 'Saving…' : 'Save board'}
            </button>
            {editing && (
              <button type="button" className="btn btn-sm btn-quiet" onClick={() => setEditing(false)}>
                Cancel
              </button>
            )}
            {data.provider !== 'NONE' && (
              <button
                type="button"
                className="link-button"
                disabled={busy}
                onClick={() => run(() => api.signals.setBoard(companyId, { none: true }), 'Marked as having no job board')}
              >
                They don’t use one of these
              </button>
            )}
            {data.provider && (
              <button
                type="button"
                className="link-button"
                disabled={busy}
                onClick={() => run(() => api.signals.setBoard(companyId, {}), 'Board removed')}
              >
                Remove board
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
