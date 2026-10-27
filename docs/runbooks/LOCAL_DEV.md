# Local development

Run commands from the repository root. Read `AGENTS.md` and
`docs/context/practices/*.md` before making changes.

## What runs locally

One Kotlin/Spring Boot application serves HTML/Markdown pages, REST APIs, Google
login, and a Streamable HTTP MCP server. There is no separate frontend build.

| Component | Local address / configuration |
| --- | --- |
| Application | `http://localhost:7331` |
| Browser login | `http://localhost:7331/login` |
| Published pages | `http://localhost:7331/p/{id}` |
| MCP | `http://localhost:7331/mcp` |
| PostgreSQL 17 | `localhost:7332`, database/user/password: `htmlshare` |
| Local profile | `src/main/resources/application-local_dev.properties` |
| Database container | Root `compose.yml`, service `postgres` |
| Local Grafana (LGTM) | `http://localhost:7333` |
| Local OTLP HTTP collector | `http://localhost:4318` |

Use the **`local_dev`** Spring profile for local development.

## 1. Check prerequisites

Requires mise, a Java 25 toolchain, and Docker with Compose. The smoke commands
below also use `curl`, `jq`, and Bash.

```bash
mise install
docker info
docker compose version
```

## 2. Start the app

```bash
mise run start
```

This activates `local_dev`, starts PostgreSQL and LGTM through `compose.yml`, applies
Flyway migrations, and starts the app on port 7331. Keep it running in a dedicated
terminal or managed background process. Wait for application startup to complete.

Direct Gradle equivalent:

```bash
./gradlew bootRun --args='--spring.profiles.active=local_dev'
```

### Optional: use your own Google OAuth client

The local profile supplies Google OAuth configuration. To use another client,
create a Google OAuth **Web application** client with this authorized redirect URI:

```text
http://localhost:7331/login/oauth2/code/google
```

Add your account as an OAuth test user if the consent screen is in testing mode.
Set these overrides before starting the app:

```bash
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID='<client-id>'
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET='<client-secret>'
```

### Local telemetry and auth troubleshooting

`local_dev` exports application logs, HTTP traces (100% sampling), and
Micrometer metrics to the pinned `grafana/otel-lgtm` container. Ports are bound to
loopback only, and data persists in the `htmlshare-lgtm` Docker volume. This is
development infrastructure, not a production deployment. Normal profiles and
automated tests do not export telemetry by default.

Open `http://localhost:7333` and use Grafana Explore. The image's default local
login is `admin` / `admin`. Select Loki for logs, Tempo for traces, or Prometheus
for metrics. Filter logs with `{service_name="htmlshare"}`. Auth events report
`returnUrlPresent` and request method/path/status, never cookies, OAuth codes,
state values, tokens, query strings, or request bodies. Trace IDs connect request
logs to traces. Do not enable blanket Spring Security TRACE logging or HTTP wire
logging, which can expose credentials.

Start just the backend manually with `docker compose up -d lgtm`. In IntelliJ,
keep using `local_dev` and restart the app after dependency changes.

For a logged-out MCP regression, use a separate browser profile, then start MCP
authorization with `scope=openid+email+profile`. Complete Google sign-in and
verify the **Approve MCP access** page appears before an authorization code is
issued. The nested return URL must preserve literal plus signs and existing
percent escapes. Avoid clearing all browser cookies or all Spring sessions to
reproduce this: isolate the browser profile instead.

## 3. Verify readiness

In another terminal, from the same repository root:

```bash
docker compose ps
docker compose exec -T postgres pg_isready -U htmlshare -d htmlshare
curl --fail-with-body http://localhost:7331/login -o /dev/null
curl --fail-with-body \
  http://localhost:7331/.well-known/oauth-protected-resource | jq -e \
  '.resource == "http://localhost:7331/mcp"'
```

## 4. Exercise publishing, updates, and committed state

REST `/api/**` uses HTTP Basic authentication, independently of Google login.
Defaults are `htmlshare:htmlshare`. To change them, export
`HTMLSHARE_API_USERNAME` and `HTMLSHARE_API_PASSWORD` **before starting the app**.
Use the same values in this terminal.

```bash
BASE_URL=http://localhost:7331
API_USER=${HTMLSHARE_API_USERNAME:-htmlshare}
API_PASSWORD=${HTMLSHARE_API_PASSWORD:-htmlshare}

PUBLISHED=$(curl --fail-with-body -sS \
  -u "$API_USER:$API_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"title":"local-dev: smoke test","content":"<h1>Local version 1</h1>","mimeType":"text/html"}' \
  "$BASE_URL/api/pages")
printf '%s\n' "$PUBLISHED" | jq -e \
  '.version == 1 and (.url | startswith("http://localhost:7331/p/"))'
PAGE_URL=$(printf '%s\n' "$PUBLISHED" | jq -er '.url')
PAGE_ID=${PAGE_URL##*/}

curl --fail-with-body -sS -u "$API_USER:$API_PASSWORD" \
  "$BASE_URL/api/pages/$PAGE_ID" | jq -e '.version == 1'

curl --fail-with-body -sS -u "$API_USER:$API_PASSWORD" \
  -X PUT -H 'Content-Type: application/json' \
  -d '{"html":"<h1>Local version 2</h1>"}' \
  "$BASE_URL/api/pages/$PAGE_ID" | jq -e '.version == 2'

curl --fail-with-body -sS -u "$API_USER:$API_PASSWORD" \
  "$BASE_URL/api/pages/$PAGE_ID" | jq -e '.version == 2'

curl --fail-with-body -sS -u "$API_USER:$API_PASSWORD" \
  "$BASE_URL/api/agent/pages/$PAGE_ID/contents" | grep -F 'Local version 2'
```

Independently inspect committed PostgreSQL rows after publishing/updating:

```bash
docker compose exec -T postgres psql -U htmlshare -d htmlshare -c \
  "SELECT p.id, p.current_version, v.version, v.html
   FROM html_pages p JOIN html_page_versions v ON v.page_id = p.id
   WHERE p.title = 'local-dev: smoke test'
   ORDER BY p.created_at DESC, v.version;"
```

For the newly created page, expect current version 2 and both version rows with
their distinct HTML contents.

## 5. Verify the browser flow

1. Open the returned `$PAGE_URL` without a browser session. Expect a redirect to
   `/login` that preserves the artifact URL.
2. Complete Google sign-in with an allowed account.
3. Confirm the browser returns to the page and renders **Local version 2**.
4. Refresh and confirm the session remains authenticated.

Use **Share** to select **Anyone with the link** and save. Open the same URL in
an incognito window: expect the artifact and Download action, without Share,
avatar, or sign-in controls. Download must return the raw HTML/Markdown, not the
viewer wrapper. Set it back to Internal and verify anonymous page, content, and
download requests enter the login flow instead of serving the artifact. New
artifacts start Internal. The viewer's status endpoint returns 401 when login is
required so the viewer can reload into that same flow.

Sharing lasts 30 days and never renews implicitly. Inspect `html_pages.public_until`
to verify the committed deadline; do not shorten deadlines on someone else's
artifacts for testing. An expired artifact can be explicitly shared again.

Browser pages use Google login; REST endpoints use Basic auth. If interactive
login is unavailable, report browser verification as blocked.

## 6. Verify MCP when working on agent integration

Configure an OAuth-capable MCP client with Streamable HTTP URL
`http://localhost:7331/mcp`.

Use the client's normal OAuth discovery, dynamic registration, PKCE, browser
Google login, and consent flow. `/mcp` expects a bearer token, not REST Basic auth.
Discovery is available at `/.well-known/oauth-authorization-server` and
`/.well-known/oauth-protected-resource`.

MCP access tokens last **1 hour**. Refresh tokens last **30 days** and rotate on
each successful refresh, starting a new 30-day window. PostgreSQL stores only
SHA-256 refresh-token hashes and their user/client/expiry metadata in
`mcp_refresh_tokens`. Rotation consumes the old hash and inserts the new hash in
one transaction, so only one concurrent refresh succeeds. Backend restarts do
not lose refresh tokens. Tokens issued before migration V18 were memory-only
and require a one-time reauthorization after restart.

Every refresh token belongs to an existing `users` row through a non-null
`user_id` foreign key. Issuance rejects identities absent from `users`, deleting
a user cascades to their refresh tokens, and refresh reads current profile
claims from that user row. Migration V20 backfills matching legacy tokens and
revokes orphaned tokens rather than creating users from credentials.

Authorization codes remain single-use, memory-only, and valid for two minutes.
Migration V19 persists the RSA signing key in `mcp_signing_keys`. All backend
instances reuse it, so access tokens remain valid across restarts. Only public
key material is served by `/oauth2/jwks`. Treat the database and its backups as
secret-bearing infrastructure: the private signing key must remain recoverable
to sign tokens and is not hashed. Restrict database access and encrypt storage
and backups. Do not delete or replace the signing key during routine deployment.

After authorization, initialize and list tools. Expect `publish_html`,
`read_file_contents`, `update_html`, `edit_file_contents`, and
`enable_live_reload`, plus `get_artifact_metadata` and `set_artifact_visibility`.
Exercise publish/read/update with a uniquely titled local
fixture, then independently check REST metadata, committed database contents,
and browser rendering. Check JSON-RPC errors and `result.isError` as well as HTTP
status codes.

## 7. Run automated checks

```bash
./gradlew test bootJar
./gradlew test --tests '*McpServerIntegrationTests' --tests '*McpOAuthServiceTests'
./gradlew spotlessCheck
```

Unit tests run without an application context. Application-context, artifact
sharing, and MCP lifecycle tests use disposable PostgreSQL 17 Testcontainers
databases with Flyway migrations;
Docker is required. Tests do **not** replace normal `local_dev` startup with
PostgreSQL, real Google login, or a successful persisted publish/update flow.
Report what was actually verified.

## 8. Stop

Stop the app with Ctrl-C (or stop the managed process). To stop PostgreSQL while
keeping its data:

```bash
docker compose stop postgres
```
