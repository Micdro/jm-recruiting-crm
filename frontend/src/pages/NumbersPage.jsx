import { useState } from 'react';
import { Link } from 'react-router';
import { api, errorMessage } from '../api.js';
import { Empty, ErrorNote, Loading, PageHeader } from '../components/Bits.jsx';
import WeekChart, { Legend } from '../components/WeekChart.jsx';
import { formatDate } from '../format.js';
import { useLoader, useTitle } from '../hooks.js';

/** One color per channel, the same everywhere on the page. */
const CHANNELS = [
  { key: 'linkedin', channel: 'LINKEDIN', label: 'LinkedIn', color: '#2a78d6' },
  { key: 'email', channel: 'EMAIL', label: 'Email', color: '#eb6834' },
  { key: 'call', channel: 'CALL', label: 'Call', color: '#1baf7a' },
];
const REPLIES = [{ key: 'replies', label: 'Replies', color: 'var(--green)' }];
const SEQUENCE_CHANNEL = ['LinkedIn', 'Email', 'LinkedIn', 'Email'];

const touchesIn = (week) => week.linkedin + week.email + week.call;
const NUMBER = new Intl.NumberFormat('en-US');
const count = (value) => NUMBER.format(value);
const counted = (value, one, many) => `${count(value)} ${value === 1 ? one : many}`;

/** "12%", "4.5%", or a dash when nothing was sent. */
function rate(replies, touches) {
  if (!touches) return '–';
  const value = (replies / touches) * 100;
  return `${value < 10 && value > 0 ? value.toFixed(1) : Math.round(value)}%`;
}

export default function NumbersPage() {
  useTitle('Numbers');
  const [scope, setScope] = useState('mine');
  const report = useLoader(() => api.reports.weekly(scope), [scope], `numbers:${scope}`);
  const data = report.data;

  return (
    <div className="numbers">
      <PageHeader
        title="Numbers"
        sub={data ? `The last 12 weeks, Monday to Sunday. This week so far, to ${formatDate(data.today)}.` : null}
      >
        <fieldset className="segmented segmented-small">
          <legend className="visually-hidden">Whose numbers</legend>
          {[
            ['mine', 'My numbers'],
            ['team', 'Team'],
          ].map(([value, label]) => (
            <label key={value} className={scope === value ? 'segment segment-on' : 'segment'}>
              <input type="radio" name="scope" value={value} checked={scope === value} onChange={() => setScope(value)} />
              {label}
            </label>
          ))}
        </fieldset>
      </PageHeader>

      {!data && report.loading && <Loading label="Adding up the numbers" />}
      {report.error && <ErrorNote>{errorMessage(report.error)}</ErrorNote>}
      {data && <Report data={data} team={scope === 'team'} />}
    </div>
  );
}

function Report({ data, team }) {
  const weeks = data.weeks;
  const thisWeek = weeks[weeks.length - 1];
  const lastWeek = weeks[weeks.length - 2];
  const anything = weeks.some((week) => touchesIn(week) || week.replies || week.meetings);

  if (!anything) {
    return (
      <section className="panel">
        <Empty title="Nothing logged in the last 12 weeks">
          The numbers fill in as touches, replies and meetings are logged. Start on <Link to="/">Today</Link>.
        </Empty>
      </section>
    );
  }

  const sent = data.byChannel.reduce((sum, row) => sum + row.touches, 0);
  const answered = data.byChannel.reduce((sum, row) => sum + row.replies, 0);

  return (
    <>
      <dl className="stat-row">
        <Stat
          label="Touches this week"
          value={touchesIn(thisWeek)}
          note={`${data.weeklyTarget ? `Target ${data.weeklyTarget}. ` : ''}Last week ${touchesIn(lastWeek)}.`}
        />
        <Stat label="Replies this week" value={thisWeek.replies} note={`Last week ${lastWeek.replies}.`} />
        <Stat label="Meetings this week" value={thisWeek.meetings} note={`Last week ${lastWeek.meetings}.`} />
        <Stat label="New contacts reached" value={thisWeek.started} note={`Last week ${lastWeek.started}.`} />
        <Stat label="Reply rate" value={rate(answered, sent)} note={`${counted(answered, 'reply', 'replies')} to ${counted(sent, 'touch', 'touches')}, 12 weeks.`} />
      </dl>

      <div className="numbers-grid">
        <section className="panel numbers-wide" aria-labelledby="touches-chart">
          <div className="panel-head">
            <h2 id="touches-chart">Touches per week</h2>
            <Legend series={CHANNELS} target={data.weeklyTarget} />
          </div>
          <WeekChart
            weeks={weeks}
            series={CHANNELS}
            target={data.weeklyTarget}
            label={`Touches per week by channel for the last 12 weeks. This week ${touchesIn(thisWeek)}. The table below has every number.`}
            describe={(week) => [`${counted(touchesIn(week), 'touch', 'touches')} in all`]}
          />
        </section>

        <section className={team ? 'panel' : 'panel numbers-wide'} aria-labelledby="replies-chart">
          <div className="panel-head">
            <h2 id="replies-chart">Replies per week</h2>
          </div>
          <WeekChart
            weeks={weeks}
            series={REPLIES}
            label={`Replies per week for the last 12 weeks. This week ${thisWeek.replies}.`}
            describe={(week) => [counted(week.replies, 'reply', 'replies'), counted(week.meetings, 'meeting', 'meetings')]}
          />
        </section>

        {team && (
          <section className="panel" aria-labelledby="team-week">
            <div className="panel-head">
              <h2 id="team-week">This week by person</h2>
            </div>
            <table className="table table-compact">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col" className="num">Touches</th>
                  <th scope="col" className="num">Replies</th>
                  <th scope="col" className="num">Meetings</th>
                </tr>
              </thead>
              <tbody>
                {data.people.map((person) => (
                  <tr key={person.userId}>
                    <th scope="row" className="table-label">
                      {person.name}
                    </th>
                    <td className="num">{person.touches}</td>
                    <td className="num">{person.replies}</td>
                    <td className="num">{person.meetings}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        <section className="panel" aria-labelledby="by-touch">
          <div className="panel-head">
            <h2 id="by-touch">Which touch gets replies</h2>
          </div>
          <TouchRates rows={data.byTouch} />
          <p className="panel-foot">
            Replies that came before the next touch, out of the cold-sequence touches sent in these 12 weeks.
          </p>
        </section>

        <section className="panel" aria-labelledby="by-channel">
          <div className="panel-head">
            <h2 id="by-channel">By channel</h2>
          </div>
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Channel</th>
                <th scope="col" className="num">Touches</th>
                <th scope="col" className="num">Replies</th>
                <th scope="col" className="num">Reply rate</th>
              </tr>
            </thead>
            <tbody>
              {CHANNELS.map((item) => {
                const row = data.byChannel.find((entry) => entry.channel === item.channel);
                return (
                  <tr key={item.key}>
                    <th scope="row" className="table-label">
                      <span className="swatch" style={{ background: item.color }} />
                      {item.label}
                    </th>
                    <td className="num">{count(row.touches)}</td>
                    <td className="num">{count(row.replies)}</td>
                    <td className="num">{rate(row.replies, row.touches)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="panel-foot">Every touch counts here, follow-ups after a reply included.</p>
        </section>

        <section className="panel numbers-wide" aria-labelledby="week-table">
          <div className="panel-head">
            <h2 id="week-table">Week by week</h2>
          </div>
          <div className="table-scroll">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th scope="col">Week of</th>
                  {CHANNELS.map((item) => (
                    <th key={item.key} scope="col" className="num">
                      {item.label}
                    </th>
                  ))}
                  <th scope="col" className="num">Touches</th>
                  <th scope="col" className="num">Replies</th>
                  <th scope="col" className="num">Meetings</th>
                  <th scope="col" className="num">New contacts</th>
                </tr>
              </thead>
              <tbody>
                {[...weeks].reverse().map((week, index) => (
                  <tr key={week.start}>
                    <th scope="row" className="table-label">
                      {formatDate(week.start)}
                      {index === 0 ? ' (so far)' : ''}
                    </th>
                    {CHANNELS.map((item) => (
                      <td key={item.key} className="num">
                        {week[item.key]}
                      </td>
                    ))}
                    <td className="num">{touchesIn(week)}</td>
                    <td className="num">{week.replies}</td>
                    <td className="num">{week.meetings}</td>
                    <td className="num">{week.started}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </>
  );
}

function Stat({ label, value, note }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd>{value}</dd>
      <dd className="stat-note">{note}</dd>
    </div>
  );
}

/** Reply rate for touches 1 to 4 as bars, each labeled with its rate and counts. */
function TouchRates({ rows }) {
  const rates = rows.map((row) => (row.touches ? row.replies / row.touches : 0));
  const most = Math.max(...rates);
  return (
    <ul className="rate-bars">
      {rows.map((row, index) => (
        <li key={row.touch}>
          <span className="rate-label">
            Touch {row.touch} <span className="muted">{SEQUENCE_CHANNEL[index]}</span>
          </span>
          <span className="rate-track">
            {rates[index] > 0 && (
              <span className="rate-bar" style={{ width: `calc((100% - 120px) * ${rates[index] / most})` }} />
            )}
            <span className="rate-value">
              {row.touches ? (
                <>
                  {rate(row.replies, row.touches)}{' '}
                  <span className="muted">
                    {count(row.replies)} of {count(row.touches)}
                  </span>
                </>
              ) : (
                <span className="muted">None sent</span>
              )}
            </span>
          </span>
        </li>
      ))}
    </ul>
  );
}
