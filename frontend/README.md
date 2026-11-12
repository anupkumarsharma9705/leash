# Leash console

The operator console for Leash: a React + TypeScript view of agent budgets, the
payment ledger, and the approval queue. It talks only to the Spring Boot admin
API at `/api/v1/admin/**`.

## Run locally

1. Start PostgreSQL from the repository root: `docker compose up -d postgres`.
2. Start the backend on port 8080 with admin credentials, for example:

   ```powershell
   $env:SPRING_PROFILES_ACTIVE = 'local'
   $env:ADMIN_USERNAME = 'admin'
   $env:ADMIN_PASSWORD_HASH = '<bcrypt hash>'
   cd backend
   .\mvnw.cmd spring-boot:run
   ```

3. In this directory:

   ```powershell
   npm install
   npm run dev
   ```

   Vite serves the console on <http://localhost:5173> and proxies `/api` and
   `/actuator` to `http://localhost:8080`, so the XSRF cookie stays first-party.

## Checks

```powershell
npm run build   # TypeScript strict build plus production bundle
npm run lint    # oxlint
```

## How the browser authenticates

The console signs in with HTTP Basic. The credential is held in module memory
only (never local storage) and sent on every admin request. The first admin GET
makes the backend issue the readable `XSRF-TOKEN` cookie; the API client mirrors
that value in `X-XSRF-TOKEN` on every write. A `401` from any admin request
clears cached data and returns to the sign-in screen.

## Layout

- `src/api/` — the only place that performs HTTP; wire types and ProblemDetail
  mapping live here.
- `src/auth/` — in-memory session state and the sign-in screen.
- `src/components/` — shell, badges, money-aware budget bar, and the loading /
  empty / error state views.
- `src/features/` — one folder per console section.
- `src/styles/` — design tokens with light and dark values, then base and
  component styles.
