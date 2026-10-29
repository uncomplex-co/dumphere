# SamePage

AI-native artifact sharing for teams. Publish Markdown snippets, reports, and
interactive HTML pages directly from AI agent sessions through MCP, then share
the link with teammates, customers, or other agents.

See [the product summary](docs/context/summary.md) for details.

## Local development

Requires Java 25, Docker with Docker Compose, and Google OAuth credentials.
Configure a Google OAuth Web application client with the redirect URI
`http://localhost:7331/login/oauth2/code/google`.

```bash
export GOOGLE_CLIENT_ID='your-google-client-id'
export GOOGLE_CLIENT_SECRET='your-google-client-secret'
mise run start
```

Or run Gradle directly:

```bash
./gradlew bootRun --args='--spring.profiles.active=local_dev'
```

The `local_dev` profile starts PostgreSQL automatically using `compose.yml`.
Open http://localhost:7331/login. PostgreSQL is available on port 7332 with
database, username, and password all set to `htmlshare`.

REST endpoints use HTTP Basic authentication (`htmlshare:htmlshare` by default).
Override these with `HTMLSHARE_API_USERNAME` and `HTMLSHARE_API_PASSWORD`.
Artifacts start Internal and require Google sign-in. Use Share to allow anyone
with the link to view and download for 30 days. Public access then expires
automatically; the URL stays the same.

See [the local development runbook](docs/runbooks/LOCAL_DEV.md) for end-to-end checks.

Without `local_dev`, Docker Compose is disabled and database connection settings
must be supplied explicitly.

Run tests with `./gradlew test`.
