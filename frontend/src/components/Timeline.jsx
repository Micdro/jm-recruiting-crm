import { Link } from 'react-router';
import { ACTIVITY_TYPE } from '../labels.js';
import { formatTimestamp } from '../format.js';
import { Empty, Loading } from './Bits.jsx';

/**
 * The activity log for a record, newest first. `show` controls which related
 * records are linked on each entry (for example the contact on a company page).
 */
export default function Timeline({ activities, loading, show = [] }) {
  if (!activities && loading) return <Loading label="Loading history" />;
  if (!activities || activities.length === 0) {
    return <Empty title="No activity yet">Touches, replies, notes and stage changes will appear here.</Empty>;
  }

  return (
    <ol className="timeline">
      {activities.map((activity) => {
        const system = activity.type === 'STATUS_CHANGE' || activity.type === 'STAGE_CHANGE';
        return (
          <li key={activity.id} className={`timeline-item ${system ? 'timeline-system' : ''}`}>
            <div className="timeline-line">
              <span className={`timeline-type type-${activity.type.toLowerCase()}`}>
                {ACTIVITY_TYPE[activity.type] ?? activity.type}
              </span>
              {activity.touch && <span className="timeline-touch">Touch</span>}
              <time className="timeline-time" dateTime={activity.occurredAt}>
                {formatTimestamp(activity.occurredAt)}
              </time>
            </div>
            {activity.summary && <p className="timeline-summary">{activity.summary}</p>}
            <p className="timeline-meta">
              {activity.userName ?? 'Someone'}
              {show.includes('contact') && activity.contactId && (
                <>
                  {' with '}
                  <Link to={`/contacts/${activity.contactId}`}>{activity.contactName}</Link>
                </>
              )}
              {show.includes('candidate') && activity.candidateId && (
                <>
                  {' with '}
                  <Link to={`/candidates/${activity.candidateId}`}>{activity.candidateName}</Link>
                </>
              )}
              {show.includes('search') && activity.searchId && (
                <>
                  {' on '}
                  <Link to={`/searches/${activity.searchId}`}>{activity.searchTitle}</Link>
                </>
              )}
              {show.includes('company') && activity.companyId && !activity.contactId && (
                <>
                  {' at '}
                  <Link to={`/companies/${activity.companyId}`}>{activity.companyName}</Link>
                </>
              )}
            </p>
          </li>
        );
      })}
    </ol>
  );
}
