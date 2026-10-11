# JM Recruiting CRM: frontend

React 19 and Vite. See the main [README](../README.md) for running the whole app.

```
npm install
npm run dev     # http://localhost:5173, forwards /api to the backend on 8080
npm run lint
npm run build   # production build in dist/ (the Docker image serves it from Spring Boot)
```

- `src/pages/` one file per screen
- `src/forms/` create and edit forms
- `src/components/` shared pieces (layout, modal, fields, timeline, tally meter, activity logging)
- `src/labels.js` display names and colors for every status, stage and type
- `src/api.js` every API call, with session and CSRF handling
- `src/hooks.js` data loading with an in-memory cache: pages show what they showed last time, then refresh
- `src/importMapping.js` column and value matching for CSV imports, including ClickUp exports
