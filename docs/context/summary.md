# Product summary

## What the app does

**dumphere** is an AI-native artifact-sharing tool for teams. Team members publish
Markdown snippets, notes, reports, and interactive HTML pages directly from their
AI agent sessions through dumphere's MCP server, then share them with teammates
and other AI agents.

The key use case is **turn an artifact into a link teammates can open immediately**,
without setting up a website or deploying a separate application. The primary
workflow is **ask the agent to publish → the agent calls MCP → share the returned
link → teammates view in a browser or another authorized agent reads the source
back through MCP using the page ID from the link**. Artifacts can move between
people and agent sessions: another agent can read the published content, use it
as context, and continue editing or updating the same artifact without changing
its link.

It is a lightweight publishing service, not a general-purpose file drive or a
browser-based document editor. This summary describes implemented behavior, not
a roadmap.

The current product name is **dumphere**. Existing `htmlshare` identifiers in code
and configuration and the `dropbox` deployment/MCP name are implementation names,
not the product name.

## Key user-facing features

### Publish HTML or Markdown and get a link

- Submit a complete HTML document, HTML fragment, or Markdown source with an
  optional title through MCP (`file_name` supplies the title). REST publishing
  requires a title, content, and MIME type.
- Each publication gets a randomly generated page ID and a stable `/p/{id}` URL.
  No separate draft/release step is required.
- HTML is served unchanged, supporting custom layouts, styling, and scripts
  within the deployment's content-security policy.
- Markdown becomes a styled, responsive HTML page. Supported extensions include
  tables, strikethrough, task lists, heading IDs for linking, footnotes, automatic
  links, and YAML front matter parsing. Raw HTML in Markdown is escaped and
  unsafe link URLs are sanitized.
- Content must be nonempty and fit the configured size limit: **5 MiB of UTF-8
  content by default**. This applies to both publishing and updates.

### Share Internally or With External Readers

- New and existing artifacts are **Internal** by default. Team members sign in
  through `/login` with Google to view and manage them.
- Any authenticated team member can switch an artifact to **Anyone with the link**
  through the viewer's Share dialog, MCP, or REST. The URL stays the same.
- Public artifacts allow anonymous viewing and raw downloads without a sign-in
  prompt. Anonymous viewers never get sharing or account controls.
- Public access ends exactly **30 days** after it is enabled. Reading, downloading,
  editing, copying the URL, or setting an already-public artifact to public again
  never extends the deadline. After expiry, a team member can explicitly enable
  a fresh 30-day window. Switching to Internal revokes public access immediately.
- Visibility is derived from one nullable `html_pages.public_until` timestamp.
  Every viewer/content/download/status request enforces it; expiry does not
  depend on a scheduled job. Previously downloaded content cannot be revoked.
- A deployment can restrict browser/MCP access to a single email domain. With no
  domain configured, any authenticated account is allowed.
- Browser sessions are stored in the database, with a configured 12-hour timeout.
- There are **no per-page invitations, ownership checks, or reader/editor permissions**.
  An authorized user who knows a page ID can access it; an authorized publishing
  client can update it regardless of who created it.

### View and Download Artifacts

- Every artifact has a compact header and a full-width sandboxed content iframe.
  The header shows its title, a format-specific HTML/Markdown icon, visibility,
  and a Download action. Signed-in team members also see their Google avatar
  (initials as fallback) and a Share action; the avatar has no menu.
- The Share dialog offers Internal / Anyone with the link and always lets the
  user copy the page URL. Team members see remaining public days in the header
  and the exact deadline in the dialog.
- Download returns the original UTF-8 source as `.html` or `.md`, not the viewer
  wrapper or rendered Markdown. Download access follows the artifact's visibility.
- Anonymous requests for an internal or expired artifact show an access-blocked
  page, without a sign-in button or the artifact's title/content.

### Revise content without changing the link

- Replace the full source or apply a targeted text replacement to an existing
  page. Both HTML and Markdown can be revised; updates retain the original format.
- Targeted editing tries exact matching first, with fallback matching for
  whitespace, indentation, escaped text, and surrounding block context. It can
  replace a single match or all occurrences and preserves existing Windows line
  endings for targeted edits. Unresolved or ambiguous matches fail rather than
  reporting success. An empty search string replaces the entire source.
- Publishing starts at version 1. Each content update/edit adds a stored version
  and increments the current version while preserving the URL.
- Metadata exposes title, format, URL, timestamps, current version, byte count,
  live status, effective visibility, and the public deadline. Creator/updater identities are recorded internally, but there
  is no author display UI and current metadata mapping does not populate author
  names.
- Stored versions are **not** a user-facing history/restore feature: there is no
  exposed API or UI to browse, diff, fetch, or restore historical versions.

### Keep an open page refreshed during iteration

- Enable or disable live reload per page through MCP or REST. It is off by default.
- The viewer checks access and the current version every **10 seconds**. Live
  artifacts refresh their content frame when the version changes. An expiry
  timer also removes an anonymous viewer's content at its deadline when offline.
- This is polling-based preview, not real-time collaborative editing. Toggling
  live status or visibility does not create a new content version.

### Let AI agents publish and maintain pages

The Streamable HTTP MCP endpoint is `/mcp`. OAuth-capable clients can discover
authentication settings, register themselves, sign in through Google, and ask
the user to **Approve** or **Deny** access to act as them.

| MCP tool | User outcome |
| --- | --- |
| `publish_html` | Publish HTML or Markdown and receive page metadata/link. |
| `read_file_contents` | Inspect current source with numbered lines and pagination. |
| `update_html` | Replace all source while retaining the link and format. |
| `edit_file_contents` | Change a selected text/block or replace all matches. |
| `enable_live_reload` | Enable/disable automatic viewer refresh for a page. |
| `get_artifact_metadata` | Inspect metadata, effective visibility, and public deadline without changing anything. |
| `set_artifact_visibility` | Set `internal` or `public` access; public access is limited to 30 days. |

Agent source reads default to 2,000 lines, truncate individual lines after 2,000
characters, and cap displayed content at 50 KiB. They are inspection output, not
a lossless file-download interface. MCP remains authenticated even for public artifacts.

MCP access tokens last five minutes, with rotating refresh tokens valid for
24 hours from issuance. Tokens/grants and signing keys are process-local, so
restarting the app requires clients to authorize again.

### Supporting REST API

MCP is the primary product interface for publishing from AI agent sessions. REST
is a supporting interface for scripts, integrations, and verification.

REST endpoints support HTTP Basic authentication using configurable application
credentials. This is separate from Google browser login and MCP bearer tokens.
The REST filter chain does not apply the Google email-domain restriction.

| Operation | Endpoint |
| --- | --- |
| Publish | `POST /api/pages` |
| Read metadata / current version | `GET /api/pages/{id}` / `GET /api/pages/{id}/version` |
| Replace content | `PUT /api/pages/{id}` |
| Toggle live reload | `POST /api/pages/{id}/live` |
| Change visibility | `POST /api/pages/{id}/visibility` with `{"visibility":"internal"}` or `{"visibility":"public"}` |
| Read numbered source | `GET /api/agent/pages/{id}/contents` |
| Apply text edits | `POST /api/agent/pages/{id}/edit` |

REST accepts HTML/XHTML and Markdown MIME types; `text/plain` is accepted when
the supplied title has a supported HTML or Markdown extension. These operations
submit text content, not multipart file uploads.

## Product boundaries

- Browser UI consists of Google sign-in, MCP consent, published-page viewing,
  sharing controls, and raw downloads.
  There is no homepage/dashboard, page listing/search, upload form, visual/source
  editor, folders, tags, or account-management interface.
- There are no exposed delete, rename, format-conversion, or custom-expiry
  operations; no comments or collaborative editing.
- HTML is not sanitized, but content runs in a sandboxed iframe with an opaque
  origin, isolated from the signed-in viewer and sharing controls. Direct content
  responses enforce the same sandbox through CSP. Script execution depends on
  the configured policy. The page policy blocks network connections
  and form submission and allows HTTPS/data images and inline styling. This is
  not unrestricted web-app hosting; separate static-asset hosting is not provided.
- The app exposes tools to external AI agents; it does not generate content or
  run its own chat/LLM workflow.

## Where to look

- `src/main/kotlin/dev/uncomplex/dumphere/application/`: publishing, versioning,
  editing, rendering, user identities, and MCP tool operations.
- `src/main/kotlin/dev/uncomplex/dumphere/adapters/api/`: REST, page viewing,
  Google login, and access rules.
- `src/main/kotlin/dev/uncomplex/dumphere/adapters/mcp/`: agent tool definitions,
  OAuth discovery/registration, token exchange, and consent.
- `src/main/resources/db/migration/`: persisted pages, versions, users, sessions,
  and registered OAuth clients. New content is stored in PostgreSQL; legacy
  filesystem publications remain readable.
- [Local development runbook](../runbooks/LOCAL_DEV.md): startup and end-to-end
  verification. The runtime is Kotlin on JDK 25, Spring Boot 4.1, and Spring AI 2.0.
