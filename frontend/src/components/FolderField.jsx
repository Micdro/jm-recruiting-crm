import { useEffect, useRef } from 'react';
import { api } from '../api.js';
import { useLoader } from '../hooks.js';
import { TextField } from './Fields.jsx';

/**
 * The shared sync folder's path on this computer. Offers the Google Drive and OneDrive folders the
 * app finds, and fills in the likeliest one: for joining, a folder that already holds the CRM.
 */
export default function FolderField({ value, onChange, joining = false, label = 'Sync folder', hint, ...props }) {
  const found = useLoader(() => api.setup.folders(), [], 'setup:folders');
  const suggestions = found.data ?? [];
  const filled = useRef(false);

  const likeliest = joining ? suggestions.find((folder) => folder.holdsCrm) : suggestions.find((folder) => !folder.holdsCrm);
  useEffect(() => {
    if (!filled.current && likeliest && !value) {
      filled.current = true;
      onChange(likeliest.path);
    }
  }, [likeliest, value, onChange]);

  const others = suggestions.filter((folder) => folder.path !== value);

  return (
    <div className="folder-field">
      <TextField
        label={label}
        value={value}
        onChange={onChange}
        placeholder="G:\My Drive\JM CRM Sync"
        spellCheck={false}
        autoComplete="off"
        hint={hint}
        {...props}
      />
      {others.length > 0 && (
        <div className="folder-suggestions">
          <span className="muted">Found on this computer:</span>
          {others.map((folder) => (
            <button key={folder.path} type="button" className="btn btn-sm" onClick={() => onChange(folder.path)}>
              {folder.path}
              {folder.holdsCrm ? ' (has the CRM)' : ''}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
