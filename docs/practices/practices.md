# Practices catalog

Use this catalog throughout a coding task to load practices on demand as you discover and enter different areas of the codebase. You do not need to know the full implementation scope upfront. Paths are relative to this catalog.

## When to load practices

- Unconditionally read `general.md`, `observability.md`, and `tests/tests.md` when starting every coding task, even if no logging or test changes are anticipated. These are the baseline practices.
- Load the remaining practices on demand as their areas become relevant; do not read every file upfront.
- Before designing, implementing, or reviewing work in a new layer, technology, or concern, check this catalog and read the matching practices. Do this at each transition, not just at task startup.
- Use what you discover in the codebase, not only the original task description, to select practices. Exploration may reveal a migration, transaction boundary, external call, or browser interaction that was not apparent initially.
- Read the matching practices before making decisions or edits in that area. If you discover a relevant practice after starting work, read it immediately and check the affected decisions and edits against it before continuing.
- Match both the work and the technology. Rules are cumulative: JVM backend data-access work requires `jvm/architecture.md`, `jvm/java.md`, and `jvm/kotlin-jpa.md`, in addition to the baseline practices. A database schema change also requires `database.md`.
- Practices already read and still available in context need not be reread at every transition. After context loss or compaction, reload the relevant practices before resuming work in that area.
- Treat this catalog as a routing guide, not a replacement for the practices themselves.

### Example: a task that crosses layers

A task starts as “add a booking cancellation feature”; its implementation is discovered incrementally:

1. **Explore:** Read `general.md`, `observability.md`, and `tests/tests.md`, then inspect the codebase to find the affected components.
2. **Database:** Discover that cancellation needs a new column. Before modelling or changing the database, read `database.md`, whether this work is standalone or part of a backend change.
3. **Backend:** Move to the cancellation use case. Before designing or editing it, read `jvm/architecture.md`. If the backend is JVM-based, also read `jvm/java.md`, including when working in Kotlin or Spring Boot.
4. **Data access:** Discover that the JVM backend's entity or repository must change. Before updating the data access layer, read `jvm/kotlin-jpa.md`.
5. **Frontend:** Move to the web cancellation control. Read `tests/tests-frontend.md` before writing frontend code, regardless of whether this step includes writing tests.
6. **Tests:** Use the baseline testing practices already loaded, plus any relevant area-specific practices not yet loaded.

This is an example, not a required sequence. If you start with tests or frontend work, load those practices first. Returning to another layer uses the same checkpoint.

## Catalog

| File | Read when | Covers |
| --- | --- | --- |
| [general.md](general.md) | **Always, at task startup.** | Pragmatic simplicity, explicit intent, maintainability, package naming, and logical grouping within methods. |
| [observability.md](observability.md) | **Always, at task startup.** | Semantic operation logging, log levels by outcome, database logging boundaries, and user activity signals. |
| [tests/tests.md](tests/tests.md) | **Always, at task startup.** | Test scope and intent, Arrange/Act/Assert, assertions, fixed execution paths, mocks and shared test doubles, committed-state verification, and browser test IDs. |
| [tests/tests-frontend.md](tests/tests-frontend.md) | Working on a web frontend, including writing application code, not only tests. Do not wait until browser tests are being added. | Placement, naming, scoping, and shared definitions of stable `data-testid` selectors. |
| [jvm/architecture.md](jvm/architecture.md) | Working on **any backend**, regardless of language or framework; the `jvm/` directory does not limit this trigger. | Hexagonal architecture, use-case naming and structure, commands and sealed results, validation boundaries, exception handling, and transactions. |
| [jvm/java.md](jvm/java.md) | Working on a **JVM backend**, including Java, Kotlin, and Spring Boot, not only when editing Java files. | Local type inference, imports, local variables and parameters, and JSpecify nullability defaults. |
| [jvm/kotlin-jpa.md](jvm/kotlin-jpa.md) | Updating the **data access layer of a JVM backend**, not only when editing Kotlin entities. | Entity shape and identity, generated values, JSON columns, schema ownership, repositories, query behavior, and persistence review checks. |
| [database.md](database.md) | A task or step involves **modelling a database or updating an existing database**, whether standalone or part of backend work. | Schema naming, timestamp ownership, soft and hard deletion, archival, foreign keys, and domain uniqueness constraints. |

## Common combinations

Always load the three baseline files first. The following are additional practices to load when entering the corresponding work, not bundles to load upfront.

- **Web frontend code or tests:** `tests/tests-frontend.md`.
- **Non-JVM backend:** `jvm/architecture.md`.
- **JVM backend, including Kotlin:** `jvm/architecture.md`, `jvm/java.md`.
- **JVM backend data-access update:** `jvm/architecture.md`, `jvm/java.md`, `jvm/kotlin-jpa.md`; add `database.md` if modelling or changing the database.
- **Standalone database modelling or migration:** `database.md`.
