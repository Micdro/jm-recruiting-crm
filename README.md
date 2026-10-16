# JM Recruiting CRM

[![CI](https://github.com/Micdro/jm-recruiting-crm/actions/workflows/ci.yml/badge.svg)](https://github.com/Micdro/jm-recruiting-crm/actions/workflows/ci.yml)

The internal CRM for Jane Michael AI Talent Search (Jane Michael LLC). It runs the firm's two
loops in one place:

- **Business development.** Outbound to AI-native vendors that sell into banks, insurers and
  other regulated institutions. One row per person, only the next action is tracked, at most
  four touches per cold sequence, and a daily target of 30 to 50 touches.
- **Specialist searches.** From intake to placement: the search brief, the candidate pipeline,
  a focused shortlist with a rationale for each candidate, and the placement fee.

Built with Java 21, Spring Boot 4, PostgreSQL (Flyway migrations), React 19 and Vite.

## What's in it

| Page | What it's for |
| --- | --- |
| **Today** | Your queue of contacts, client follow-ups and candidates due now, with one-click logging (LinkedIn, Call, Replied) and **Write** for the next message from a template. Tally marks show today's touches against the 30 target and 50 stretch, for you and the team. Pull new contacts into today when the queue runs short. Open searches, fees this year and guarantees ending soon sit alongside. A short affirmation sits under the date, the same for the whole team each day; there are 60, so none repeats for two months. |
| **Contacts** | Buyers and hiring managers, with their buying role. Status, touch count (four boxes), next action, owner. The sequence is four LinkedIn touches (connection request, first message, follow-up, last message), three business days apart. The fourth touch ends the sequence, and 90 days later (never on a weekend) the contact comes back on **Today** with **Restart**, which starts four new touches with the first due that day (and can be undone); a reply puts a response on today's list. New contacts are pulled into today one company at a time, so no account gets four messages in a morning. Email can still be logged, but the CRM never suggests it. |
| **Companies** | The ClickUp client list: stage (Lead through Complete), priority, tier, industry, segments, hiring need and timeline, anticipated hires, lead source and a follow-up date. Logging a LinkedIn message, call, email or meeting records the contact and clears a follow-up that was due. Opening a search moves the company to Placing. |
| **Hiring signals** | Open forward deployed and AI platform roles at companies in the CRM, read from their public Greenhouse, Lever and Ashby job boards (no account or key needed). The CRM looks each company's board up from its website and name on its own: every 15 minutes it takes a batch of up to 40 companies with no board on file, never-checked ones first, and tries again every 30 days for any it couldn't find. That covers imports, companies added by hand and ones accepted on AI Found (companies still waiting on AI Found, and ones marked Not qualified or Not interested, are skipped). **Find job boards** does all of them at once. Boards found that way wait for someone to confirm them, and a board can also be pasted on the company's page. Each computer reads the boards every six hours while someone is signed in and keeps the matching roles in a local, encrypted cache that isn't synced; which board a company uses, and when the team last reviewed it, sync with the company. Roles posted in the last 30 days and since the last **Mark reviewed** show as new, here and on Today, with the people at the company to message. **Write** next to a person opens the message composer about one of the company's openings: `{role}` fills in with its title, a template that uses `{role}` is picked first (or a built-in signal message), and the logged touch notes the role. **Candidates who fit** ranks people in the CRM who are open to a move against the roles (track, deploy or operate, regulated experience, location and the title's words), leaving out the company's own people and anyone at an off-limits client, with **Blind profile** to make a PDF prepared for that role. |
| **AI Found** | A prospect finder that searches the web with Claude each weekday for AI and security vendors selling into banks, insurers and financial services that show a reason to hire now: open forward deployed or AI platform roles, new funding, or a new bank or insurer customer. Each company arrives in its own **AI found** stage with why it was picked, the source links, and up to three people who would hire, with their LinkedIn profiles. Work emails and phone numbers are added only when they're published, and any LinkedIn page, email or phone number that didn't appear in the search results is left out, so nothing is a guess. **Accept** makes it a lead and puts its people in the outreach queue; **Dismiss** moves it to Not qualified (never suggested again) and its untouched people to the Trash. Companies already in the CRM, under any name or website, are skipped. One computer runs the weekday search, the first time the CRM is unlocked after 7 AM; **Find now** runs one any time. The owner adds the Claude API key (checked before it's saved, stored encrypted and shared) and sets a daily limit ($2 to start) and a monthly limit ($30): a search stops before it would pass either, saving what it found so far, and no search starts with less than $1 left. Admins set the number of companies per search and extra guidance. Each search's cost is shown. |
| **Searches** | The role, terms (contingency, exclusive or retained, fee percent), and the brief: production outcome, technical scope, accountability, regulatory constraints. A pipeline board from Sourced to Placed. Moving someone to Shortlisted asks for the rationale the client will read. The Shortlist tab prints a clean slate (or saves it as PDF). |
| **Interviews** | **Schedule interview** on a pipeline card (shortlisted, submitted, interviewing or offer) records the date, an optional time and what it is ("Round 2 with the CTO"), and moves the candidate to Interviewing. On the day it shows on **Today**; once it's over (an hour after it starts, or the next day without a time) Today asks for two debriefs, one with the candidate and one with the client, each with notes that go in the candidate's, search's and company's history. The card shows the same. Scheduling the next round starts both debriefs over; the interview can be moved or cancelled from the card. The morning reminder counts interviews today and debriefs to do. Candidates no longer in process drop off. |
| **Candidates** | Production AI engineers and technical leaders, with production evidence (what they owned, decided and fixed), regulated-industry experience, applicable roles (design, deploy, operate), experience, work preference, employment type, searches and follow-ups. |
| **Placements** | Start date, base, fee (calculated from the search terms unless entered), invoice status and the 90-day guarantee. |
| **Guarantee check-ins** | 30 and 60 days after a placement starts, **Today** asks you to check in with the candidate and the client while the guarantee runs, so a problem shows up while it can still be fixed. **Check in** takes notes for the candidate's, search's and company's history. A missed 30-day check-in doesn't leave two to do: one check-in covers every point that has come. Placements shows the next check-in under the guarantee date, and the morning reminder counts the ones due. |
| **Invoices** | **Create invoice** on a placement makes a PDF invoice for its fee: your firm's details and payment instructions (entered once and shared with the team), the next invoice number for the year, invoice and due dates from your terms, who it's billed to, and the placement details. The placement is marked invoiced; overdue invoices show on Today and in red on Placements. Billing details and payment instructions are encrypted. |
| **Numbers** | The last 12 weeks: touches per week by channel against the weekly target, replies, meetings and new contacts reached, the reply rate for each of the four touches and each channel, and the team's week by person. A reply counts toward the touch just before it and whoever sent that touch. |
| **Morning reminder** | On Windows, a notification each weekday morning with what's due today for the person on that computer: sequence touches, replies to answer, follow-ups, candidates and clients, and how many new contacts would reach the daily target. Click it to open the CRM. Set the time or turn it off on **This computer**. |
| **Export to Excel** | **Export** on Contacts, Companies, Candidates, Searches and Placements downloads the records the page shows, after its filters, with every field and the notes. The **Export** page downloads everything as one workbook: companies, contacts, searches, candidates, pipeline, placements and activity. The file is made in the browser and isn't encrypted. |
| **Resume files** | Add a candidate's resume (PDF, Word, OpenDocument, RTF or text, up to 10 MB) on their page: click **Add file** or drop it on the panel. The file and its name are encrypted with the CRM key before they're stored, so the database alone can't read them. On the desktop app each file reaches the other computers as its own encrypted file in the sync folder. Opening a PDF shows it in the browser; other kinds download. |
| **Blind profiles** | **Blind profile** on a candidate makes a PDF to send a client before they've signed: headline, experience, focus, work preferences and what they've owned in production, under a reference like JM-GJDH17 instead of their name. Their name, email, phone, LinkedIn and employer are taken out of the text (the name becomes "the candidate"), and anything that still identifies them is pointed out before download. Choosing a search adds "Prepared for" and your rationale. Each profile is noted in the candidate's history. |
| **Candidate matches** | A **Matches** tab on each search ranks candidates already in the CRM who aren't in it yet and are open to a move: same track, regulated financial services experience, location (or remote, or open to relocating), pay expectation against the base range, seniority for senior roles, and words their production evidence shares with the brief's scope. Each shows what fits and what might not, with **Add to search**. |
| **Off-limits** | The firm doesn't recruit from a client while it works for them (a signed client or an open search), nor for a year after the last placement there starts. **Off-limits until** on a company replaces that year when a contract says otherwise; today ends it. Candidates whose current employer is off-limits are tagged, warned about on their page and in the candidate form, left out of matches (by name), and need **Add anyway** to join a search. Company names match loosely: case, accents, "Inc." and words like "AI" don't matter. Each company page lists the candidates who work there. |
| **Progress reports** | **Progress report** on a search makes a one-page PDF for the client: how many candidates were identified, contacted, assessed, shortlisted, submitted, interviewed and offered (from each candidate's furthest stage), what moved since the last report, who is with the client and how long they've waited for feedback, then **What we need from you** (in bold, drafted with dates) and **Next steps**. Candidates can be shown by reference (JM-…) instead of name. Each report is noted in the search history, so the next one covers what changed since. |
| **Quick search** | Press **Ctrl+K** (**⌘K** on a Mac) anywhere, or click **Search** in the sidebar, and type part of a name, email, title or company to jump to any contact, company, candidate or search, or to a page. Arrow keys and Enter, or click. Searching happens in the browser on the records already loaded, so it's instant and works with encrypted fields. |
| **Add from LinkedIn** | A bookmark (set up once on the **Add from LinkedIn** page) that, on someone's LinkedIn profile or Sales Navigator lead, opens the CRM with their name, title, company, location and profile link filled in. Save them as a contact (adding their company if it's new) or as a candidate. It warns when the person is already in the CRM, and when LinkedIn shows them in a new job (a new employer, or a new title in an "X at Y" headline) it offers **Update contact** or **Update candidate**: their record with the new job filled in, to check and save, with the move noted in their history. A contact who moved can be put at their new company in one step. Nothing leaves the computer except to this CRM. |
| **Log from LinkedIn** | A second bookmark, **Log to JM CRM**, set up on the same page. In a LinkedIn conversation (the messaging page or a chat window) it reads who the conversation is with, who wrote last and their last message, and opens the CRM on that contact or candidate (matched by profile link, then name). **Log their reply** or **Log my message** logs it in one click, suggested from who wrote last, with their message in the note; it warns when the same thing was already logged today, and offers to add someone who isn't in the CRM. It reads only the page that's open, when it's clicked; LinkedIn offers no way for other apps to read messages, so nothing syncs on its own. |
| **Trash and undo** | Deleting a company, contact, candidate, search, placement or file shows **Undo** for a few seconds. Deleted records also wait in the **Trash** (in the sidebar) for 30 days, with everything that went with them (history, pipeline, files), and any computer can restore them. The Trash is encrypted and syncs like the rest. |
| **Undo a touch** | Logging a touch, reply, meeting or note on a contact or candidate (one-click on Today, **Write**, or the log form) shows **Undo**. It puts the record back as it was (touch count, status, next action, last contacted) and removes the history entry, so the day's tally and Numbers drop it too. Undo works for 10 minutes, for the person who made the change, on the computer they made it on; if someone else has changed the record since, it says so and leaves it alone. |
| **Later** | **Later** on each Today row (contacts, client follow-ups and candidates) moves the follow-up to **Tomorrow** (the next business day, so Friday goes to Monday) or **Next week** (the same weekday a week out), without opening the record. Each choice shows the day it lands on. The move is noted in the history and can be undone like a touch. |
| **Bulk actions** | Tick rows on **Contacts** or **Companies** (or tick the header box for every row the filters show; shift-click ticks a range) and a bar of actions appears. Contacts: change the owner or status, **Add to today**, **Restart sequence**, or delete. Restart sequence puts contacts back to New with no touches and nothing scheduled, so **Pull in** adds them to Today a few companies at a time; it's how to bring back sequences that ended before revisits existed. Companies: change the owner, stage or priority, **Follow up today**, or delete. Adding to today gives unassigned contacts and companies to you, as **Pull in** does, and leaves out contacts marked Do not contact or Not interested, sequences that are over, and anything already due. Companies with contacts or searches aren't deleted. Whatever was left alone is listed with the reason. Status and stage changes are noted in each record's history, deletes go to the Trash, and **Undo** reverses the whole batch for 10 minutes, on the computer it was made on (records someone changed since are left as they are). |
| **Templates** | Saved LinkedIn messages for each of the four touches, shared with the team (email templates still work if you need one). Placeholders like `{first name}` and `{company}` fill in for each contact, and `{role}` with the opening when writing from Hiring signals. **Write** on the Today queue (or **Write message** on a contact) picks the template for their next touch, then copies it, opens their LinkedIn profile and logs the touch. Four starter messages to begin with, and a starter for any touch that has no LinkedIn message yet. |
| **Import** | Upload ClickUp CSV exports (outbound pipeline, buyer list, client list, candidate list) or any spreadsheet. ClickUp's column names and values are recognised, statuses are translated, owners (MD, DD, names or emails) are matched to the team, duplicates are skipped or updated, and missing companies are created. |
| **Team** | Admins add teammates, reset passwords and deactivate accounts. Everyone can change their own password. One admin is the **owner** (at first, whoever set up the CRM): the only one who can change the AI Found API key and spending limits. Other admins can't demote, deactivate or reset the password of the owner; the owner hands the role on with **Make owner**. |

Everything a person does is recorded in the activity history on the record it touches.

## Windows app (each computer keeps its own copy)

The team runs the CRM as a Windows app. Each computer has its own built-in database, so pages
open and save instantly, and the copies sync through a shared Google Drive (or OneDrive) folder,
usually within a minute. No server, no open ports, no VPN.
Setup steps for the team: **[docs/WINDOWS.md](docs/WINDOWS.md)**.

How it works:

- `JM CRM.exe` (built by CI with jpackage) starts PostgreSQL from files bundled in the app, keeps
  its data in `%LOCALAPPDATA%\JMCRM`, and serves the CRM on http://localhost:4780 only.
- Every change to a shared table is versioned by a database trigger. Every few seconds each
  computer writes what changed into its own subfolder of the shared folder (`computer-N/`, numbered
  files of up to 1,000 changes) and applies the other computers' new files in order. Applying
  follows the same rules however a change arrives: the newest version of a row wins, rows wait for
  their parents, a delete that would orphan someone else's new record is undone, and duplicate
  pipeline entries or accounts collapse to one.
- Each file is gzipped JSON sealed with AES-256-GCM under a key derived from the sync token (which
  comes from the CRM key), bound to its path so files can't be swapped. The cloud provider sees
  only ciphertext. Files are written under a temporary name and renamed; one that hasn't fully
  arrived fails its check and is read on a later round.
- After 200 files a computer folds them into one snapshot, so the folder stays small. A computer
  that fell behind reads the snapshot instead, and a new computer joins by reading everything in
  the folder. The computers never need to be on at the same time.
- **Direct connections (optional, per computer).** A computer with them on opens its sync port
  (4781), asks the home router to forward it (UPnP IGD, renewed hourly) and lists its home and
  internet addresses in its sealed `about` file in the folder. The others connect to it every few
  seconds: they fetch its changes and, if it can't reach them in turn, send it theirs, so one
  reachable computer is enough. Requests are signed with HMAC-SHA256 under a key derived from the
  sync token (the token itself never travels), must be within ten minutes of the server's clock,
  and bodies are sealed with AES-256-GCM both ways, each response bound to its request. An address
  that keeps failing is shut out for a while. The folder keeps working alongside; nothing depends
  on direct connections.
- Each computer creates ids in its own range (computer 2 from 2,000,000,001), so new records never
  collide.
- Encrypted fields travel encrypted inside the sealed files; every computer shares the same key.
- **Locked until someone signs in.** The CRM key is never written to disk. Each account holds its
  own copy of the key, locked with that person's password (Argon2id, 64 MiB, then AES-256-GCM),
  stored with the account and synced like any other row. Signing in opens it in memory; the CRM
  locks again when the last person on that computer signs out, their session expires, or the app
  closes. While locked, data requests get 423 and only sync (which copies scrambled rows) runs.
  The CRM key from the password manager can unlock an account that has no copy yet, or set a new
  password when one is forgotten.
- **Updates.** CI numbers each build (`-Djmcrm.build`) and publishes `latest.json` (build number
  and SHA-256) next to the zip on the `windows-latest` release. The app checks it every six hours;
  **Update now** downloads the zip, checks its hash, unpacks it beside the app, and starts a small
  PowerShell script that waits for the app to close, swaps the folders (keeping the old one until
  the swap worked, or copying over it if a file is held open), and starts the new version. Data
  and settings live in `%LOCALAPPDATA%\JMCRM`, so they carry over. Each computer notes its version
  in the sync folder, so the others can say when it needs updating.
- The same app still deploys as a server (below); a server could later join the group as one more
  computer.

## Run it locally

You need Java 21, Node 22 and PostgreSQL 16 (or Docker).

### Option A: Docker (simplest)

```
export APP_ENCRYPTION_KEY="$(openssl rand -base64 32)"   # save it in your password manager
ADMIN_EMAIL=you@jane-michael.com ADMIN_PASSWORD='a-long-password' docker compose up --build
```

Use the same `APP_ENCRYPTION_KEY` every time you start it.

Open http://localhost:8080 and sign in with that email and password. Data is kept in the
`jmcrm-data` Docker volume.

### Option B: Development servers

1. Create a database: `createdb jmcrm`
2. Copy `backend/src/main/resources/application-example.properties` to
   `application.properties` in the same folder and fill in your database password, an
   encryption key (`openssl rand -base64 32`) and the admin email and password you want.
3. Run `start-dev.bat` (Windows), or in two terminals run `cd backend && ./mvnw spring-boot:run`
   and `cd frontend && npm install && npm run dev`.
4. Open http://localhost:5173. The React dev server forwards `/api` to the backend on 8080.

The admin account is created the first time the backend starts with no users. After that,
add Jane and Dimitrios from the Team page.

**Upgrading an existing local database:** Flyway takes over the schema. Tables created by the
earlier version are kept (old free-text statuses are mapped to the new ones, and the old
relationship status is copied into notes). Set `spring.jpa.hibernate.ddl-auto=none` in your
`application.properties` if it still says `update`.

## Deploy

The repo builds one Docker image that serves the API and the React app together.

### Render (blueprint included)

1. In Render, choose **New > Blueprint** and select this repository. `render.yaml` creates the
   web service and a PostgreSQL database in Virginia.
2. Enter `ADMIN_EMAIL` and `ADMIN_PASSWORD` when asked. They create your account on first start.
   Render generates `APP_ENCRYPTION_KEY` for you.
3. **Copy `APP_ENCRYPTION_KEY` from the service's Environment tab into your password manager
   now.** It is the only way to read the encrypted fields, including in database backups.
   Never change it on a running system.
4. When the deploy finishes, open the service URL and sign in. Add a custom domain such as
   `crm.jane-michael.com` in the service settings if you want one.

The database only accepts connections from the app over Render's private network
(`ipAllowList: []`). To connect from your own machine for a one-off task, add your IP address
in the database's settings and remove it afterwards.

### Anywhere else that runs containers

Build with `docker build -t jm-recruiting-crm .` and set:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | `postgresql://user:password@host:5432/dbname` (or set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) |
| `APP_ENCRYPTION_KEY` | **Required.** 32 random bytes, base64 (`openssl rand -base64 32`). Encrypts sensitive fields. Keep a copy in your password manager; losing it loses that data. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NAME` | First admin account, created when no users exist. Password at least 12 characters. |
| `DB_SSLMODE` | TLS to the database. Defaults to `prefer` (TLS whenever offered). Use `verify-full` for a database reached over the public internet. |
| `COOKIE_SECURE` | `true` when served over HTTPS (always in production) |
| `APP_TIMEZONE` | Time zone for "today" and daily touch counts. Defaults to `America/New_York`. |
| `PORT` | Port to listen on. Defaults to 8080. |

## Security

- **Encryption at rest.** Everything that identifies a person or company is encrypted with
  AES-256-GCM before it reaches the database: names, titles, employers, emails, phone numbers,
  LinkedIn pages, locations, websites, notes, compensation, salaries, fees, search titles and
  briefs, shortlist rationales and activity notes. A stolen database or backup is unreadable
  without the key. Emails and company names also get a keyed fingerprint (HMAC-SHA256) so
  duplicates are still caught. Existing plain-text rows are encrypted automatically the next time
  the CRM starts (server) or is unlocked (Windows app). Teammates' own names and emails stay
  readable, since signing in needs them.
- **No key on disk (Windows app).** See "Locked until someone signs in" above. A server reads its
  key from `APP_ENCRYPTION_KEY` instead.
- **Passwords** are at least 12 characters and hashed with Argon2id (19 MiB memory, 2
  iterations, the OWASP setting). Older bcrypt hashes are upgraded the next time that person
  signs in.
- **Sign-in throttling.** Five wrong passwords for one account, or twenty from one address,
  within 15 minutes block further attempts for the rest of that window.
- **Sessions** last 12 hours. Deactivating someone ends their session on their next request.
- **Browser protections.** A strict Content-Security-Policy (only the app's own scripts, styles
  and fonts), HSTS, no framing, no referrer leaks and no camera, microphone or location access.
- **In transit.** HTTPS to the browser; TLS to the database whenever it's offered.
- CSRF protection on every change, and every `/api` endpoint except the health check requires
  sign-in. Admin-only actions are checked on the server.

## Speed

- Lists load once and filter in the browser, so searching and filtering are instant.
- Pages you've opened show immediately the next time and refresh in the background. After
  sign-in, the main lists load quietly so the first visit to each page is instant too. This copy
  lives only in the open tab's memory: nothing is saved to disk, and sign-out clears it.
- Responses are gzip-compressed, and the app's files are cached by the browser for a year
  (each release gets new file names, so updates still show up straight away).
- Fonts ship with the app; nothing is fetched from third parties.

**Install it like a desktop app.** In Chrome or Edge, open the CRM and click the install icon at
the right end of the address bar, or choose Install from the browser menu. It gets its own
window, Dock or taskbar icon and shortcuts to Today, Contacts, Companies and Candidates. On an
iPhone, use **Share > Add to Home Screen**.

## Tests

### Backend

```
cd backend
./mvnw verify
```

Unit tests cover the outreach rules, CSV import and helpers. `ApiIntegrationTest` runs the API
against a real PostgreSQL database with real security; each test rolls back.

### End to end (Playwright)

```
cd e2e
npm install
npx playwright install chromium
npx playwright test
```

The tests sign in as `ADMIN_EMAIL` / `ADMIN_PASSWORD` (defaults: `e2e-admin@jane-michael.com` /
`e2e-admin-password`), so start the backend with those set or point them at an existing account.
Playwright reuses a running backend and frontend, or starts them. Every test deletes what it
creates; test records have `E2E` in their names.

### CI

GitHub Actions (`.github/workflows/ci.yml`) runs on pushes to `main` and `feature/**` and on
pull requests:

1. Backend unit and integration tests against PostgreSQL
2. Frontend lint and production build
3. Playwright API and UI tests against the full stack
4. The production Docker image build

Failures are posted as annotations on the commit, including the backend log when the
end-to-end job fails.

## Project layout

```
backend/    Spring Boot API (Java 21). One package per area: auth, company, contact,
            activity, search, candidate, pipeline, placement, dashboard, importer.
            Schema migrations in src/main/resources/db/migration.
frontend/   React app. pages/ for screens, forms/ for create and edit forms,
            components/ for shared pieces, labels.js for every status and stage name.
e2e/        Playwright tests (api/ and ui/), signing in once in auth.setup.js.
Dockerfile, docker-compose.yml, render.yaml   Build and hosting.
```
