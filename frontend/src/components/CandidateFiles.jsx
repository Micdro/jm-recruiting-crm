import { useRef, useState } from 'react';
import { api, errorMessage } from '../api.js';
import { useUndoableDelete } from '../undo.js';
import { READABLE_TYPES } from '../candidateAi.js';
import { formatDate, toIsoDate } from '../format.js';
import { useLoader, useToast } from '../hooks.js';
import { ConfirmButton, ErrorNote } from './Bits.jsx';

const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = '.pdf,.docx,.doc,.odt,.rtf,.txt';
const KIND = {
  'application/pdf': 'PDF',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word',
  'application/msword': 'Word',
  'application/vnd.oasis.opendocument.text': 'OpenDocument',
  'application/rtf': 'RTF',
  'text/plain': 'Text',
};

function size(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Resumes and other files on a candidate: added here, encrypted in the CRM, shared with the team.
 * With `onRead`, a resume can fill in what the record is missing: AI reads it and hands back a draft.
 */
export default function CandidateFiles({ candidateId, onRead }) {
  const toast = useToast();
  const undoable = useUndoableDelete();
  const files = useLoader(() => api.candidates.files(candidateId), [candidateId], `candidate:${candidateId}:files`);
  const input = useRef(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [reading, setReading] = useState(null);

  async function read(file) {
    setReading(file.id);
    setError('');
    try {
      onRead(await api.candidates.readFile(candidateId, file.id));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setReading(null);
    }
  }

  async function add(list) {
    const chosen = [...list];
    if (chosen.length === 0) return;
    const tooBig = chosen.find((file) => file.size > MAX_BYTES);
    if (tooBig) {
      setError(`${tooBig.name} is over 10 MB. Save a smaller copy and add that.`);
      return;
    }
    setBusy(true);
    setError('');
    try {
      for (const file of chosen) {
        await api.candidates.addFile(candidateId, file);
      }
      toast(chosen.length === 1 ? `Added ${chosen[0].name}` : `Added ${chosen.length} files`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
      files.reload();
      if (input.current) input.current.value = '';
    }
  }

  const list = files.data ?? [];

  return (
    <section
      className={`panel candidate-files ${dragging ? 'candidate-files-drop' : ''}`}
      aria-labelledby="files-heading"
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setDragging(false);
      }}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        add(event.dataTransfer.files);
      }}
    >
      <div className="panel-head">
        <h2 id="files-heading">Resume and files</h2>
        <label className={`btn btn-sm ${busy ? 'btn-disabled' : ''}`}>
          {busy ? 'Adding…' : 'Add file'}
          <input
            ref={input}
            type="file"
            className="visually-hidden"
            accept={ACCEPT}
            multiple
            disabled={busy}
            onChange={(event) => add(event.target.files)}
          />
        </label>
      </div>
      <ErrorNote>{error || (files.error && errorMessage(files.error))}</ErrorNote>

      {list.length === 0 ? (
        <p className="muted">
          {files.data ? 'No files yet. ' : ''}Add a resume or drop it here: PDF, Word, OpenDocument, RTF or text, up to 10 MB.
          Files are encrypted and shared with the team.
        </p>
      ) : (
        <ul className="file-list">
          {list.map((file) => (
            <li key={file.id}>
              <div className="file-text">
                {file.available ? (
                  <a href={api.candidates.fileUrl(candidateId, file.id)} target="_blank" rel="noopener" className="file-name">
                    {file.fileName}
                  </a>
                ) : (
                  <span className="file-name">{file.fileName}</span>
                )}
                <span className="muted">
                  {size(file.sizeBytes)} {KIND[file.contentType] ?? 'file'}, added {formatDate(toIsoDate(new Date(file.createdAt)))}
                  {file.uploadedByName ? ` by ${file.uploadedByName}` : ''}
                </span>
                {!file.available && (
                  <span className="file-pending">On its way from the computer it was added on. Check back in a minute.</span>
                )}
              </div>
              <div className="file-actions">
                {onRead && file.available && READABLE_TYPES.includes(file.contentType) && (
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={reading !== null}
                    onClick={() => read(file)}
                    title="AI reads the resume and fills in what the record is missing. You check it before it's saved."
                  >
                    {reading === file.id ? 'Reading…' : 'Fill in from this'}
                  </button>
                )}
                {file.available && (
                  <a className="btn btn-sm" href={api.candidates.fileUrl(candidateId, file.id, true)} download={file.fileName}>
                    Download
                  </a>
                )}
                <ConfirmButton
                  className="btn btn-sm btn-quiet"
                  onConfirm={async () => {
                    try {
                      await api.candidates.removeFile(candidateId, file.id);
                      undoable('FILE', file.id, `Deleted ${file.fileName}`, () => files.reload());
                      files.reload();
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
}
