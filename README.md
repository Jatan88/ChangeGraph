# ChangeGraph

ChangeGraph answers one question before a merge: what could break, why, and which tests should run.

A signed-in user creates a project, connects one public GitHub repository, ingests a commit, and reads an explainable impact report. Each project owns a separate FalkorDB graph. PostgreSQL stores users, projects, repository metadata, graph-name mappings, ingestion jobs, and analysis reports.

## Architecture

```
React (Vite)  -->  Spring Boot API  -->  PostgreSQL
                       |                 FalkorDB (one graph per project)
                       |                 GitHub public API
                       +-- parsers for Java, Python, JavaScript, and TypeScript
```

The frontend never chooses a graph name. The API generates `cg_` plus the project UUID with hyphens removed, and every project route checks ownership. A missing or unowned project returns 404.

PostgreSQL and FalkorDB are not one transaction. Project creation saves the mapping first, then initializes the graph. `graphStatus` is `READY` or `FAILED`. Retry uses the same stored name. Deleting a project is explicit: the UI requires confirmation, the API requires `confirm=true`, and the only graph deleted is the one whose stored name matches that project.

## Public data only

This version supports public GitHub repositories. It does not use GitHub OAuth or accept a token from the browser. The server parses `https://github.com/owner/name` URLs, calls `api.github.com`, and checks visibility itself. A client flag is not accepted as proof. If visibility cannot be confirmed, ingestion stops. An optional personal access token in server configuration raises GitHub's rate limit from 60 to 5,000 requests per hour. Private repositories stay rejected.

Synthetic payment incidents are optional and labeled `sourceType=SYNTHETIC` and `isSynthetic=true`. They are not created at startup and are not described as production history.

## Local setup

Requirements:

- Java 25 (this machine: `~/.sdkman/candidates/java/25.0.3-tem`)
- Node.js with npm
- PostgreSQL, only when you choose to apply migrations
- The existing FalkorDB endpoint already configured in `backend/src/main/resources/application.properties`

The Falkor host and port in `application.properties` are unchanged. Credentials stay in the gitignored `backend/src/main/resources/application-local.properties` or in `FALKORDB_USERNAME` / `FALKORDB_PASSWORD`. Do not commit that file.

```bash
cp .env.example .env
```

Fill PostgreSQL values in `.env` or export them before starting the API. Leave `FLYWAY_ENABLED=false` until you have reviewed `backend/src/main/resources/db/migration/V1__initial_schema.sql` and want it applied.

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-tem"
cd backend
export POSTGRES_URL=jdbc:postgresql://localhost:5432/changegraph
export POSTGRES_USER=changegraph
export POSTGRES_PASSWORD=change-me
export FLYWAY_ENABLED=true
./mvnw spring-boot:run
```

In another terminal:

```bash
cd frontend
npm install
npm run dev
```

The UI is at `http://localhost:5173` and proxies `/api` to `http://localhost:1212`.

Flyway is off unless `FLYWAY_ENABLED=true`. Hibernate validates the schema and does not create or alter it. Do not point the app at a database you have not approved.

## Environment variables

| Variable | Purpose |
| --- | --- |
| `POSTGRES_URL` | JDBC URL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | PostgreSQL credentials |
| `FALKORDB_USERNAME` / `FALKORDB_PASSWORD` | Optional overrides; host and port stay in `application.properties` |
| `FLYWAY_ENABLED` | `true` applies versioned migrations. Default `false`. |
| `FRONTEND_ORIGIN` | CORS origin. Default `http://localhost:5173`. |
| `SESSION_COOKIE_SECURE` | Set `true` behind HTTPS. |
| `GITHUB_TOKEN` or `changegraph.github.token` | Optional personal access token for `api.github.com`. Put it in gitignored `backend/src/main/resources/application-local.properties`, not in the browser. |
| `GITHUB_CONNECT_TIMEOUT_MS` / `GITHUB_REQUEST_TIMEOUT_MS` / `GITHUB_MAX_RETRIES` | GitHub client limits. Retries do not bypass rate limits. |
| `GITHUB_POLL_INTERVAL_MINUTES` | How often the server polls each linked repository for commits. Default 30. Opening Ingestion does not call GitHub. |
| `PASSWORD_MIN_LENGTH` | Default 10, plus one letter and one digit. |
| `AUTH_MAX_ATTEMPTS` / `AUTH_WINDOW_SECONDS` | Login and registration rate limit by remote address. |
| `INGESTION_MAX_FILES` / `INGESTION_MAX_FILE_BYTES` | Default 40 files and 200 KB each. |
| `ANALYSIS_UPSTREAM_DEPTH` / `ANALYSIS_DOWNSTREAM_DEPTH` / `ANALYSIS_MAX_NODES` | Default 3, 2, and 100. |

Frontend environment variables are not used for secrets. Session cookies are HttpOnly. The CSRF cookie `XSRF-TOKEN` is readable by the page and sent as `X-XSRF-TOKEN`.

## What is implemented

- Registration, login, logout, and `GET /api/auth/me` with a server session
- BCrypt passwords, CSRF, narrow CORS, and auth rate limiting
- Owned-project create, list, update, and confirmed delete
- Per-project FalkorDB graph mapping, failed-init status, and retry
- Public repository link, commit and pull-request reads, and patch stripping on the commit API
- Java parsing, idempotent graph merge, bounded impact traversal, and a documented 0–100 risk score
- Optional synthetic payment incidents
- Frontend flow from account creation through the impact report
- Backend tests on H2 with a mocked graph client
- Frontend tests with a mocked API

The HTTP contract is in [docs/openapi.yaml](docs/openapi.yaml).

Risk score bands: 0–24 `LOW`, 25–49 `MEDIUM`, 50–74 `HIGH`, 75–100 `CRITICAL`. The score is a deterministic baseline, not a trained model. Reports say functions are potentially impacted.

## Known limitations

- Java, Python, JavaScript, and TypeScript are parsed, including JSX and TSX. Other files are recorded as changed files. Declaration files ending in `.d.ts` are not parsed.
- Method calls resolve only when the name and argument count match one function in the ingested file set, including across Java, Python, JavaScript, and TypeScript. Unresolved calls are counted, not invented. Imports, HTTP routes, and build files do not connect languages.
- Rename detection uses GitHub `previous_filename` only.
- Ingestion reads the selected commit, parent blobs for changed source files, and Java, Python, JavaScript, and TypeScript files present in the repository at that commit, within the file and size caps. The cap is shared across those languages. Context files skip dependency and build directories such as `node_modules`, `dist`, `build`, `target`, `vendor`, and virtualenvs. A commit that only edits a README still brings in that source. Files in other languages are stored when that commit changes them.
- Service dependencies come from annotations or decorators (`Service`, `Component`, `Controller`, `RestController`, `Repository`, `Injectable`, `Module`, `Router`), not from class-name guesses.
- Synthetic incidents affect a report only when they link to a relevant parsed function. An incident report does not prove a root cause.
- Analysis runs in the request after ensuring that commit is ingested. There is no separate worker queue beyond the ingestion executor.
- The impact agent (`POST /api/projects/{projectId}/agent/investigations`) plans a bounded investigation with fixed graph tools. It uses an OpenAI-compatible chat API when `CHANGEGRAPH_LLM_API_KEY` is set (`CHANGEGRAPH_LLM_BASE_URL`, `CHANGEGRAPH_LLM_MODEL`). With no key, an invalid model response, or a provider failure, the report is marked `DETERMINISTIC_FALLBACK` and is built only from the project graph. The agent does not write incidents, run repository code, or execute model-authored queries.
- Automated tests do not call GitHub, PostgreSQL, or the configured FalkorDB instance.
- The graph explorer is a bounded node layout plus a table, not a full graph canvas.

## Commands to review

Safe checks, using an in-memory database and mocks:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-tem"
cd backend && ./mvnw test
cd frontend && npm test && npm run typecheck && npm run build
```

Applying the schema and starting the API writes to the PostgreSQL database named in `POSTGRES_URL` and initializes project graphs on the configured FalkorDB host. Run that only after review:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.3-tem"
export FLYWAY_ENABLED=true
cd backend && ./mvnw spring-boot:run
```
