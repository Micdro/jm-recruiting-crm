# JM Recruiting CRM

[![CI](https://github.com/Micdro/jm-recruiting-crm/actions/workflows/ci.yml/badge.svg)](https://github.com/Micdro/jm-recruiting-crm/actions/workflows/ci.yml)

A full-stack recruiting CRM built with:

- Java 21
- Spring Boot
- PostgreSQL
- React
- Rust
- GitHub Actions

## Architecture

React frontend
→ Spring Boot REST API
→ PostgreSQL database

Rust importer validates and imports recruiting data.

## Goals

- Create full CRM solution for Jane Michael LLC
- Implement automated testing
- Configure CI/CD pipelines
- Build deployable production-style software

## Testing

### Backend unit tests

JUnit and Mockito tests for the services and controllers. The context test needs PostgreSQL running.

```
cd backend
mvnw test
```

### End-to-end tests (Playwright)

Located in `e2e/`. Two test projects:

- `api`: CRUD, validation, and not-found checks against the REST API for companies and contacts
- `ui`: drives the React app in Chromium (create company, create contact, error handling) and confirms the data was saved through the API

```
cd e2e
npm install
npx playwright install chromium
npx playwright test             # everything
npx playwright test --project=api
npx playwright test --project=ui
npx playwright show-report      # open the HTML report
```

Playwright starts the backend and frontend automatically. If they are already running (for example from `start-dev.bat`), it reuses them. Tests run against whatever database the backend is connected to; every test deletes the records it creates, and test records have `E2E` in their names.

### CI

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request:

1. Backend tests against a PostgreSQL service container
2. Frontend lint and production build
3. Playwright API and UI tests against the full stack, with the HTML report uploaded as a build artifact
