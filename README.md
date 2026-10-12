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
| **Today** | Your queue of contacts, client follow-ups and candidates due now, with one-click logging (Email, LinkedIn, Call, Replied). Tally marks show today's touches against the 30 target and 50 stretch, for you and the team. Pull new contacts into today when the queue runs short. Open searches, fees this year and guarantees ending soon sit alongside. |
| **Contacts** | Buyers and hiring managers, with their buying role. Status, touch count (four boxes), next action, owner. The sequence follows the ClickUp outbound list: LinkedIn, email, LinkedIn, email, three business days apart. The fourth touch ends the sequence; a reply puts a response on today's list. New contacts are pulled into today one company at a time, so no account gets four emails in a morning. |
| **Companies** | The ClickUp client list: stage (Lead through Complete), priority, tier, industry, segments, hiring need and timeline, anticipated hires, lead source and a follow-up date. Logging a call, email or meeting records the contact and clears a follow-up that was due. Opening a search moves the company to Placing. |
| **Searches** | The role, terms (contingency, exclusive or retained, fee percent), and the brief: production outcome, technical scope, accountability, regulatory constraints. A pipeline board from Sourced to Placed. Moving someone to Shortlisted asks for the rationale the client will read. The Shortlist tab prints a clean slate (or saves it as PDF). |
| **Candidates** | Production AI engineers and technical leaders, with production evidence (what they owned, decided and fixed), regulated-industry experience, applicable roles (design, deploy, operate), experience, work preference, employment type, searches and follow-ups. |
| **Placements** | Start date, base, fee (calculated from the search terms unless entered), invoice status and the 90-day guarantee. |
| **Import** | Upload ClickUp CSV exports (outbound pipeline, buyer list, client list, candidate list) or any spreadsheet. ClickUp's column names and values are recognised, statuses are translated, owners (MD, DD, names or emails) are matched to the team, duplicates are skipped or updated, and missing companies are created. |
| **Team** | Admins add teammates, reset passwords and deactivate accounts. Everyone can change their own password. |

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
