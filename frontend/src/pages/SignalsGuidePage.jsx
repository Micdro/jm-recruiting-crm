import { Link } from 'react-router';
import { PageHeader } from '../components/Bits.jsx';
import { useTitle } from '../hooks.js';

/** How to get job boards on file and use hiring signals day to day. */
export default function SignalsGuidePage() {
  useTitle('Hiring signals guide');

  return (
    <>
      <Link to="/signals" className="back">
        Hiring signals
      </Link>
      <PageHeader
        title="Setting up hiring signals"
        sub="Get each company’s job board on file once, then use new roles as a reason to reach out."
      />

      <div className="guide">
        <section className="panel narrow-panel" aria-labelledby="guide-what">
          <div className="panel-head">
            <h2 id="guide-what">What it does</h2>
          </div>
          <p>
            Hiring signals reads the public Greenhouse, Lever and Ashby job boards of companies in the CRM and lists
            their open forward deployed and AI platform roles. A new role is a timely reason to message the hiring
            manager.
          </p>
          <p>
            It costs nothing. These are the same public feeds the companies’ own careers pages use, so there’s no
            account or key to set up.
          </p>
        </section>

        <section className="panel narrow-panel" aria-labelledby="guide-setup">
          <div className="panel-head">
            <h2 id="guide-setup">Set it up once</h2>
          </div>
          <ol className="plain-list numbered guide-steps">
            <li>
              <strong>Update the app.</strong> Open <Link to="/computer">This computer</Link> and click{' '}
              <strong>Update now</strong>. Do this on each computer.
            </li>
            <li>
              <strong>Find the boards.</strong> On one computer, open <Link to="/signals">Hiring signals</Link> and
              click <strong>Find job boards</strong>. It checks each company’s website and name against Greenhouse,
              Lever and Ashby. A few hundred companies take a few minutes, and the page shows progress. Board links
              sync to the other computers, so only one person needs to do this.
            </li>
            <li>
              <strong>Check the boards it found.</strong> Each one shows a yellow note. Click{' '}
              <strong>Yes, it’s theirs</strong>, or <strong>No, wrong company</strong> if the board belongs to a
              different company with a similar name.
            </li>
            <li>
              <strong>Sort out the rest.</strong> Companies listed under <strong>No job board on file</strong> usually
              use another system, like Workday, or their own site. Open each one and, under <strong>Open roles</strong>,
              either paste their board link or click <strong>They don’t use one of these</strong> so it isn’t looked
              up again.
            </li>
          </ol>
        </section>

        <section className="panel narrow-panel" aria-labelledby="guide-manual">
          <div className="panel-head">
            <h2 id="guide-manual">Add a board by hand</h2>
          </div>
          <ol className="plain-list numbered guide-steps">
            <li>
              Go to the company’s careers page. If the address starts with <code>job-boards.greenhouse.io</code>,{' '}
              <code>boards.greenhouse.io</code>, <code>jobs.lever.co</code> or <code>jobs.ashbyhq.com</code>, copy it.
              A link to one of their jobs works too.
            </li>
            <li>
              If the careers page is on their own site, open any job. The apply button often leads to one of those
              addresses.
            </li>
            <li>
              Open the company in the CRM, paste the link into <strong>Job board link</strong> under{' '}
              <strong>Open roles</strong>, and click <strong>Save board</strong>. Its matching roles show right away.
            </li>
          </ol>
        </section>

        <section className="panel narrow-panel" aria-labelledby="guide-daily">
          <div className="panel-head">
            <h2 id="guide-daily">Use it every day</h2>
          </div>
          <ol className="plain-list numbered guide-steps">
            <li>
              <strong>Today</strong> lists companies that posted new roles since the team last reviewed them.
            </li>
            <li>
              Open <strong>Hiring signals</strong> and read the new roles. Under <strong>Reach out to</strong>, click{' '}
              <strong>Write</strong> next to a person. The message names the opening (pick another under{' '}
              <strong>About the opening</strong> if the company has several), then copy it, open LinkedIn and log the
              touch, as on Today. The touch counts toward their sequence and notes the role in their history.
            </li>
            <li>
              Under <strong>Candidates who fit</strong>, click <strong>Blind profile</strong> to make a PDF of a
              matching candidate, prepared for that role, to send with your message.
            </li>
            <li>
              Click <strong>Mark reviewed</strong> once you’ve acted. The company only comes back when it posts
              something new.
            </li>
            <li>
              If a company has no contacts yet, add the hiring manager from LinkedIn first, then reach out.
            </li>
          </ol>
        </section>

        <section className="panel narrow-panel" aria-labelledby="guide-faq">
          <div className="panel-head">
            <h2 id="guide-faq">Good to know</h2>
          </div>
          <ul className="plain-list guide-notes">
            <li>
              Boards are read every six hours while someone is signed in. <strong>Check boards now</strong> reads them
              right away.
            </li>
            <li>
              Roles are matched by job title: forward deployed, deployment and applied AI engineers, and MLOps,
              LLMOps, ML or AI platform, ML infrastructure and inference engineers. Sales, recruiting and product
              roles are left out.
            </li>
            <li>A role counts as new for 30 days after it’s posted, until the company is marked reviewed.</li>
            <li>
              Write starts from a built-in message about the role. To use your own, add a template on{' '}
              <Link to="/templates">Templates</Link> with <code>{'{role}'}</code> where the role’s title goes. It’s picked
              first whenever you write from a signal.
            </li>
            <li>
              Candidates who fit are people in the CRM open to a move, matched on track, what they do (deploy or
              operate), regulated financial services experience, location and the role’s title. People at the hiring
              company, or at an off-limits client, are left out.
            </li>
            <li>
              <strong>Boards that couldn’t be read</strong> usually means the company moved or renamed its board.
              Paste the new link on its page.
            </li>
            <li>
              The roles are stored encrypted on each computer and aren’t synced. Board links and review dates sync
              with the company.
            </li>
          </ul>
        </section>
      </div>
    </>
  );
}
