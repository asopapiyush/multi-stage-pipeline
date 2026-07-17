# Deploying to Render — Step-by-Step Guide

Two Render Web Services (backend + UI) plus one Render PostgreSQL instance. Both services build from this repo's Dockerfiles directly — no separate CI pipeline or container registry needed; Render builds the image itself from the connected GitHub repo.

---

## Prerequisites

Before starting, make sure you have:

1. **A GitHub account with this repo pushed to it.** Render deploys from a connected Git repo (public or private) — it does not accept a local folder upload for Docker-based Web Services.
2. **A Render account** — [render.com](https://render.com), free to sign up, no card required for the free tier.
3. **This repo committed with all the files this session created**, specifically:
   - `Dockerfile` (repo root, backend)
   - `UI app/Dockerfile` (UI)
   - `UI app/server.js` (already moved here, reads `PORT`/`BACKEND_URL` from env)
   - `docker-compose.yml` + `.env.example` (for local testing before you deploy)
   - `src/main/resources/schema.sql` and the Postgres-flavored `JobRepository`/`UserRepository`
4. **A terminal with `openssl` available** (macOS/Linux have it built in; on Windows, Git Bash — which you're already using — has it too) to generate the JWT signing secret.
5. **(Recommended, not required) Docker Desktop installed locally**, so you can run `docker compose up` and smoke-test the whole stack on your own machine before pushing to Render. This catches config mistakes (wrong env var name, bad JDBC URL) faster than waiting on a Render build each time.
6. **Decide your admin login** — a username and a real password for the one seeded admin account (`AdminUserSeeder` creates it from `ADMIN_USERNAME`/`ADMIN_PASSWORD` on startup). No separate registration flow exists.

Nothing else is required locally — you do **not** need Java, Maven, or Node installed on your machine just to deploy; Render's Docker build stage handles compiling the Spring Boot jar and there are zero UI build dependencies (the frontend is plain HTML/JS/CSS served by a dependency-free Node script).

---

## Part 1 — Backend (Spring Boot API + WebSocket)

### 1. Create the Render Postgres instance

1. Render Dashboard → **New** → **PostgreSQL**.
2. Choose the free tier. Name it `pipeline-db`.
3. Once provisioned, open it and copy the **Internal Database URL** (services in the same Render region reach it privately — cheaper and lower latency than the External URL, which is only needed if you want to connect from your own machine with `psql`).

It looks like:
```
postgres://pipeline_db_user:AbC123XyZ@dpg-xxxxxxxxxxxx-a/pipeline_db
```

### 2. Translate the connection string for Spring

Spring's `spring.datasource.url` needs the **JDBC** form, not Render's `postgres://` URI. Split the pieces out:

| Render URL piece | → | Env var |
|---|---|---|
| `dpg-xxxxxxxxxxxx-a` (host) + `pipeline_db` (db name) | → | `SPRING_DATASOURCE_URL=jdbc:postgresql://dpg-xxxxxxxxxxxx-a/pipeline_db` |
| `pipeline_db_user` | → | `SPRING_DATASOURCE_USERNAME=pipeline_db_user` |
| `AbC123XyZ` | → | `SPRING_DATASOURCE_PASSWORD=AbC123XyZ` |

This is the single most common mistake deploying Spring Boot to Render — don't paste the `postgres://` URI directly into `SPRING_DATASOURCE_URL`; Spring's JDBC driver won't parse it.

### 3. Generate a JWT signing secret

```bash
openssl rand -base64 32
```
Copy the output — you'll paste it as `JWT_SECRET` in the next step. It must be at least 32 bytes (this command produces exactly that); a shorter value will fail HMAC-SHA256 key validation at startup.

### 4. Create the backend Web Service

1. Render Dashboard → **New** → **Web Service** → connect this repo.
2. **Root Directory**: repo root (where `Dockerfile` lives).
3. **Environment**: Docker.
4. **Instance type**: Free (or lowest paid tier). Free-tier services spin down after 15 minutes of no traffic — the first request after idle takes ~30-60s to wake up (cold start). Fine for an internal/demo tool; upgrade to a paid instance if you need it always warm.
5. **Health Check Path**: `/api/health`.
6. **Environment variables** — add each of these in the Render dashboard:

   | Key | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | from step 2 |
   | `SPRING_DATASOURCE_USERNAME` | from step 2 |
   | `SPRING_DATASOURCE_PASSWORD` | from step 2 |
   | `JWT_SECRET` | from step 3 |
   | `ADMIN_USERNAME` | your chosen admin username, e.g. `admin` |
   | `ADMIN_PASSWORD` | your chosen admin password — required, or login always fails |
   | `ALLOWED_ORIGINS` | leave blank for now — set in Part 3, once the UI's URL exists |

7. Deploy. Watch the build logs — Render runs the multi-stage `Dockerfile` (Maven build → JRE runtime), which takes a few minutes on first deploy.
8. Once live, note the assigned URL, e.g. `https://pipeline-backend-xxxx.onrender.com`.
9. Sanity check: `curl https://pipeline-backend-xxxx.onrender.com/api/health` should return `{"status":"ok"}`. If it doesn't, check the service logs — most first-deploy failures are a missing/misformatted `SPRING_DATASOURCE_URL`.

---

## Part 2 — UI (static frontend + config server)

### 5. Create the UI Web Service

1. Render Dashboard → **New** → **Web Service** → same repo.
2. **Root Directory**: `UI app` (where its `Dockerfile`/`server.js` live).
3. **Environment**: Docker.
4. **Environment variables**:

   | Key | Value |
   |---|---|
   | `BACKEND_URL` | `https://pipeline-backend-xxxx.onrender.com` (the backend's URL from step 8, **https** — Render terminates TLS at the edge even though the container listens on plain HTTP internally) |

   Do **not** set `PORT` manually — Render injects its own, and `server.js` already reads `process.env.PORT`.
5. Deploy. Note the assigned URL, e.g. `https://pipeline-ui-xxxx.onrender.com`.

---

## Part 3 — Wire the two services together

### 6. Close the CORS / WebSocket origin loop

Go back to the **backend** service (from Part 1) → Environment → set:
```
ALLOWED_ORIGINS=https://pipeline-ui-xxxx.onrender.com
```
Save — this triggers a restart. `WebSocketConfig`'s allowed origins and `WebConfig`'s CORS mapping both read this same property, so one variable covers both the REST API and the WebSocket handshake — you don't need to configure them separately.

### 7. Smoke test

1. Open `https://pipeline-ui-xxxx.onrender.com`.
2. Log in with the `ADMIN_USERNAME`/`ADMIN_PASSWORD` you set in Part 1.
3. Submit a small URL batch (2-3 Wikipedia URLs work well — see `task.md` for a ready-made list).
4. Open browser dev tools → Network → WS tab — confirm the WebSocket connects (status 101) and item/aggregate events stream in as the pipeline runs.
5. Confirm the job appears under **Job Execution History** after completion, and that reloading the page still shows it — this proves Postgres persistence (the entire reason SQLite was replaced: Render's filesystem is ephemeral, but the database isn't).

If login fails: check the backend logs for `AdminUserSeeder` output — it logs a warning and skips seeding if `ADMIN_PASSWORD` was left blank.

If the WebSocket won't connect: check that `ALLOWED_ORIGINS` (step 6) exactly matches the UI's live URL, including `https://` and no trailing slash.

---

## Local development (before you deploy)

Test the full stack on your machine first if you have Docker installed:

```bash
cp .env.example .env
# edit .env: set JWT_SECRET (openssl rand -base64 32) and ADMIN_PASSWORD
docker compose up
```

This starts Postgres + backend + UI together. Open `http://localhost:3000`. The UI's `BACKEND_URL` in `docker-compose.yml` is deliberately `http://localhost:8080` (the **browser's** view of the backend), not `http://backend:8080` (the Docker-internal DNS name) — `/config.js` is consumed client-side in the browser, which sits outside the Docker network.

---

## Rotating the admin password

Change `ADMIN_PASSWORD` on the backend Render service and save. Render restarts the container, `AdminUserSeeder` runs again on startup and upserts the new bcrypt hash into Postgres. No manual database access needed.

## What had to become environment-driven for Render to work

- `WebSocketConfig.java` — origins read from `app.allowed-origins` (was previously a hardcoded `.setAllowedOrigins("http://localhost:3000", "http://localhost:8080")`).
- `WebConfig.java` — same property, plus `Authorization` was added to the allowed CORS headers (was `Content-Type` only, which would silently reject the authenticated cross-origin requests the UI now sends).
- `UI app/server.js` — `PORT` and `BACKEND_URL` read from `process.env`; `/config.js` is generated fresh on every request so the browser always gets whatever `BACKEND_URL` the container currently has — change the env var, restart, no rebuild required.
- `UI app/app.js`'s `backendUrl()` and WebSocket URL construction — read `window.APP_CONFIG.BACKEND_URL` (populated by `/config.js`) instead of hardcoding `:8080`.
