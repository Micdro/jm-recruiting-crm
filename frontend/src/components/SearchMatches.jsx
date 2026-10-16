import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { useLoader, useToast } from '../hooks.js';
import { matchCandidates } from '../matching.js';
import { candidateOffLimits } from '../offLimits.js';
import { Empty, ErrorNote, Loading, ShowMore } from './Bits.jsx';

const LEVEL_CLASS = { 'Strong match': 'match-strong', 'Good match': 'match-good-fit', 'Possible match': 'match-possible' };

/** True when the brief has little to match on, so suggestions are rough. */
const thinBrief = (search) =>
  !search.track || (!search.location && search.workModel !== 'REMOTE') || (!search.technicalScope && !search.productionOutcome);

/**
 * Candidates already in the CRM who fit this search, best first, with what fits and what might not.
 * Scored from the brief (track, location, base range, scope) against their records.
 */
export default function SearchMatches({ search, entries, onAdded, onEditBrief }) {
  const toast = useToast();
  const candidates = useLoader(() => api.candidates.list(), [], 'candidates');
  const companies = useLoader(() => api.companies.list(), [], 'companies');
  const [shown, setShown] = useState(10);
  const [adding, setAdding] = useState(null);
  const [error, setError] = useState('');

  const inSearch = useMemo(() => new Set((entries ?? []).map((entry) => entry.candidateId)), [entries]);
  // People at off-limits companies (this client among them) aren't suggested; they're named instead.
  const { matches, leftOut } = useMemo(() => {
    const all = matchCandidates(search, candidates.data, inSearch);
    const blocked = (match) => candidateOffLimits(match.candidate, companies.data);
    return {
      matches: all.filter((match) => !blocked(match)),
      leftOut: all.filter(blocked).map((match) => ({ candidate: match.candidate, company: blocked(match) })),
    };
  }, [search, candidates.data, companies.data, inSearch]);

  async function add(candidate) {
    setAdding(candidate.id);
    setError('');
    try {
      await api.pipeline.add({ searchId: search.id, candidateId: candidate.id, stage: 'SOURCED' });
      toast(`Added ${candidate.name} to this search as sourced`);
      onAdded();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setAdding(null);
    }
  }

  if (!candidates.data) return candidates.error ? <ErrorNote>{errorMessage(candidates.error)}</ErrorNote> : <Loading label="Finding matches" />;

  return (
    <section className="panel" aria-labelledby="matches-heading">
      <div className="panel-head">
        <h2 id="matches-heading">Candidates who fit</h2>
        <span className="panel-meta">Not in this search yet. Scored on track, regulated experience, location, pay and the brief’s scope.</span>
      </div>
      {thinBrief(search) && (
        <p className="setup-note">
          The brief is missing some of what matching uses (track, location, scope).{' '}
          <button type="button" className="link-button" onClick={onEditBrief}>
            Fill in the brief
          </button>{' '}
          for better suggestions.
        </p>
      )}
      <ErrorNote>{error}</ErrorNote>
      {matches.length === 0 ? (
        <Empty title={leftOut.length ? 'No one else in the CRM fits yet' : 'No one in the CRM fits yet'}>
          Nobody open to a move matches this brief. Add candidates you’ve mapped in the market, or from LinkedIn, and they show
          up here.
        </Empty>
      ) : (
        <>
          <ol className="match-list">
            {matches.slice(0, shown).map(({ candidate, level, reasons }) => (
              <li key={candidate.id}>
                <div className="match-text">
                  <p className="match-title">
                    <Link to={`/candidates/${candidate.id}`} className="match-name">
                      {candidate.name}
                    </Link>
                    <span className={`match-level ${LEVEL_CLASS[level]}`}>{level}</span>
                  </p>
                  {(candidate.currentTitle || candidate.currentCompany) && (
                    <p className="muted">{[candidate.currentTitle, candidate.currentCompany].filter(Boolean).join(' at ')}</p>
                  )}
                  <ul className="match-reasons" aria-label="Why">
                    {reasons.map((reason) => (
                      <li key={reason.text} className={reason.good ? 'match-plus' : 'match-minus'}>
                        <span className="visually-hidden">{reason.good ? 'Fits: ' : 'Might not fit: '}</span>
                        {reason.text}
                      </li>
                    ))}
                  </ul>
                </div>
                <button type="button" className="btn btn-sm" disabled={adding === candidate.id} onClick={() => add(candidate)}>
                  {adding === candidate.id ? 'Adding…' : 'Add to search'}
                </button>
              </li>
            ))}
          </ol>
          <ShowMore shown={shown} total={matches.length} onMore={() => setShown((count) => count + 10)} />
        </>
      )}
      {leftOut.length > 0 && (
        <p className="muted match-left-out">
          Left out because they work at off-limits companies:{' '}
          {leftOut.map(({ candidate, company }, index) => (
            <span key={candidate.id}>
              {index > 0 && ', '}
              <Link to={`/candidates/${candidate.id}`}>{candidate.name}</Link> ({company.name})
            </span>
          ))}
          .
        </p>
      )}
    </section>
  );
}
