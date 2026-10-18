import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { RESUME_ACCEPT, chosenProvider, readBy, resumeProblem } from '../candidateAi.js';
import CandidateForm from '../forms/CandidateForm.jsx';
import { useLoader } from '../hooks.js';
import { ErrorNote } from './Bits.jsx';

/**
 * Drop in a resume: AI fills in the new candidate, the person checks the form and saves, and the
 * resume goes on the candidate's files.
 */
export default function AddFromResume({ onCancel, onByHand, onAdded }) {
  const ai = useLoader(() => api.ai.status(), [], 'ai:status');
  const input = useRef(null);
  const [file, setFile] = useState(null);
  const [draft, setDraft] = useState(null);
  const [reading, setReading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');

  const provider = chosenProvider(ai.data);
  const noKey = provider && !provider.hasKey;

  async function read(list) {
    const chosen = list?.[0];
    if (!chosen || reading) return;
    const problem = resumeProblem(chosen);
    if (problem) {
      setError(problem);
      return;
    }
    setFile(chosen);
    setReading(true);
    setError('');
    try {
      setDraft(await api.candidates.readResume(chosen));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setReading(false);
      if (input.current) input.current.value = '';
    }
  }

  if (draft) {
    return (
      <>
        <p className="ai-note">
          {readBy(draft.provider, draft.costCents)} from {draft.fileName}. Check each field, then save. The resume goes on
          their files.
        </p>
        {draft.matches.length > 0 && (
          <p className="warn-note" role="note">
            May already be in the CRM:{' '}
            {draft.matches.map((match, index) => (
              <span key={match.id}>
                {index > 0 && ', '}
                <Link to={`/candidates/${match.id}`}>{match.name}</Link> ({match.why})
              </span>
            ))}
            . Open that record instead if it’s the same person.
          </p>
        )}
        <CandidateForm
          candidate={draft.candidate}
          onCancel={onCancel}
          onSaved={async (saved) => {
            let attached = true;
            try {
              await api.candidates.addFile(saved.id, file);
            } catch {
              attached = false;
            }
            onAdded(saved, attached);
          }}
        />
      </>
    );
  }

  return (
    <div className="form">
      <div
        className={`resume-drop ${dragging ? 'resume-drop-over' : ''}`}
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
          read(event.dataTransfer.files);
        }}
      >
        {reading ? (
          <p className="resume-drop-reading" aria-live="polite">
            Reading {file?.name}…
          </p>
        ) : (
          <>
            <p className="resume-drop-title">Drop a resume here</p>
            <label className={`btn btn-primary ${noKey ? 'btn-disabled' : ''}`}>
              Choose a resume
              <input
                ref={input}
                type="file"
                className="visually-hidden"
                accept={RESUME_ACCEPT}
                disabled={reading || noKey}
                onChange={(event) => read(event.target.files)}
              />
            </label>
            <p className="muted">PDF, Word (.docx), OpenDocument, RTF or text, up to 10 MB.</p>
          </>
        )}
      </div>

      {noKey ? (
        <p className="warn-note" role="note">
          Reading resumes needs {provider.id === 'OPENAI' ? 'an' : 'a'} {provider.label} API key.{' '}
          {ai.data.canManage ? 'Add one in the AI settings on AI Found.' : `Ask ${ai.data.owner ?? 'an admin'} to add one.`}
        </p>
      ) : (
        <p className="field-hint">
          The resume’s text goes to {provider?.label ?? 'the AI'} to read. Nothing is saved until you check the form and save.
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>

      <div className="form-actions">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn" onClick={onByHand}>
          Fill in by hand
        </button>
      </div>
    </div>
  );
}
