import { BOARD_STAGES, STAGE } from '../labels.js';

/** A compact row of stage counts for a search: how many candidates sit at each step. */
export default function StageBar({ counts }) {
  const stages = BOARD_STAGES.filter((stage) => (counts?.[stage] ?? 0) > 0);
  if (stages.length === 0) return <span className="muted">No candidates</span>;

  const description = stages.map((stage) => `${counts[stage]} ${STAGE[stage].toLowerCase()}`).join(', ');

  return (
    <span className="stage-bar" title={description} aria-label={description}>
      {BOARD_STAGES.map((stage) => {
        const count = counts?.[stage] ?? 0;
        return (
          <span key={stage} className={`stage-step ${count > 0 ? 'stage-step-on' : ''}`} aria-hidden="true">
            {count > 0 ? count : ''}
          </span>
        );
      })}
    </span>
  );
}
