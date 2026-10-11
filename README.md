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
| **Today** | Your queue of contacts and candidates due now, with one-click logging (Email, LinkedIn, Call, Replied). Tally marks show today's touches against the 30 target and 50 stretch, for you and the team. Pull new contacts into today when the queue runs short. Open searches, fees this year and guarantees ending soon sit alongside. |
| **Contacts** | Buyers and hiring managers. Status, touch count (four boxes), next action, owner. Logging a touch moves the sequence forward and schedules the next one three business days out. The fourth touch ends the sequence; a reply puts a response on today's list. |
| **Companies** | Target accounts and clients, with their people, searches and history. Opening a search makes the company a client. |
| **Searches** | The role, terms (contingency, exclusive or retained, fee percent), and the brief: production outcome, technical scope, accountability, regulatory constraints. A pipeline board from Sourced to Placed. Moving someone to Shortlisted asks for the rationale the client will read. The Shortlist tab prints a clean slate (or saves it as PDF). |
| **Candidates** | Production AI engineers and technical leaders, with production evidence (what they owned, decided and fixed), regulated-industry experience, searches and follow-ups. |
| **Placements** | Start date, base, fee (calculated from the search terms unless entered), invoice status and the 90-day guarantee. |
| **Import** | Upload a CSV (for example the ClickUp buyer list). Columns are matched by name, your statuses are translated to the CRM's, duplicates are skipped or updated, and missing companies are created. |
| **Team** | Admins add teammates, reset passwords and deactivate accounts. Everyone can change their own password. |

Everything a person does is recorded in the activity history on the record it touches.

## Run it locally

You need Java 21, Node 22 and PostgreSQL 16 (or Docker).

### Option A: Docker (simplest)

```
ADMIN_EMAIL=you@jane-michael.com ADMIN_PASSWORD='a-long-password' docker compose up --build
```

Open http://localhost:8080 and sign in with that email and password. Data is kept in the
`jmcrm-data` Docker volume.

### Option B: Development servers

1. Create a database: `createdb jmcrm`
2. Copy `backend/src/main/resources/application-example.properties` to
   `application.properties` in the same folder and fill in your database password and the
   admin email and password you want.
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
3. When the deploy finishes, open the service URL and sign in. Add a custom domain such as
   `crm.jane-michael.com` in the service settings if you want one.

### Anywhere else that runs containers

Build with `docker build -t jm-recruiting-crm .` and set:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | `postgresql://user:password@host:5432/dbname` (or set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NAME` | First admin account, created when no users exist. Password at least 10 characters. |
| `COOKIE_SECURE` | `true` when served over HTTPS (always in production) |
| `APP_TIMEZONE` | Time zone for "today" and daily touch counts. Defaults to `America/New_York`. |
| `PORT` | Port to listen on. Defaults to 8080. |

## Security

- Session sign-in with BCrypt-hashed passwords. Sessions last 12 hours.
- CSRF protection on every change (the React app sends the `XSRF-TOKEN` cookie back as a header).
- Every `/api` endpoint except the health check requires sign-in. Admin-only actions are
  checked on the server.
- Candidate and contact data is internal. Keep the service behind HTTPS and give accounts only
  to the team.

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
