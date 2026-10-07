// Where the running app lives. Override with env vars to point the tests elsewhere.
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';

// Must stay on localhost:5173: the backend's CORS config only allows that origin.
export const UI_URL = process.env.UI_URL ?? 'http://localhost:5173';
